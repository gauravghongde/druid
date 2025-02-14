/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.data.input.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.druid.guice.annotations.PublicApi;
import org.apache.druid.java.util.common.parsers.ParserUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@PublicApi
public class DimensionsSpec
{
  private final List<DimensionSchema> dimensions;
  private final Set<String> dimensionExclusions;
  private final Map<String, DimensionSchema> dimensionSchemaMap;
  private final boolean includeAllDimensions;

  private final boolean useNestedColumnIndexerForSchemaDiscovery;

  public static final DimensionsSpec EMPTY = new DimensionsSpec(null, null, null, false, null);

  public static List<DimensionSchema> getDefaultSchemas(List<String> dimNames)
  {
    return getDefaultSchemas(dimNames, DimensionSchema.MultiValueHandling.ofDefault());
  }

  public static List<DimensionSchema> getDefaultSchemas(
      final List<String> dimNames,
      final DimensionSchema.MultiValueHandling multiValueHandling
  )
  {
    return dimNames.stream()
                   .map(input -> new StringDimensionSchema(input, multiValueHandling, true))
                   .collect(Collectors.toList());
  }

  public static DimensionSchema convertSpatialSchema(SpatialDimensionSchema spatialSchema)
  {
    return new NewSpatialDimensionSchema(spatialSchema.getDimName(), spatialSchema.getDims());
  }

  public static Builder builder()
  {
    return new Builder();
  }

  public DimensionsSpec(List<DimensionSchema> dimensions)
  {
    this(dimensions, null, null, false, null);
  }

  @JsonCreator
  private DimensionsSpec(
      @JsonProperty("dimensions") List<DimensionSchema> dimensions,
      @JsonProperty("dimensionExclusions") List<String> dimensionExclusions,
      @Deprecated @JsonProperty("spatialDimensions") List<SpatialDimensionSchema> spatialDimensions,
      @JsonProperty("includeAllDimensions") boolean includeAllDimensions,
      @JsonProperty("useNestedColumnIndexerForSchemaDiscovery") Boolean useNestedColumnIndexerForSchemaDiscovery
  )
  {
    this.dimensions = dimensions == null
                      ? new ArrayList<>()
                      : Lists.newArrayList(dimensions);

    this.dimensionExclusions = (dimensionExclusions == null)
                               ? new HashSet<>()
                               : Sets.newHashSet(dimensionExclusions);

    List<SpatialDimensionSchema> spatialDims = (spatialDimensions == null)
                                               ? new ArrayList<>()
                                               : spatialDimensions;

    verify(spatialDims);

    // Map for easy dimension name-based schema lookup
    this.dimensionSchemaMap = new HashMap<>();
    for (DimensionSchema schema : this.dimensions) {
      dimensionSchemaMap.put(schema.getName(), schema);
    }

    for (SpatialDimensionSchema spatialSchema : spatialDims) {
      DimensionSchema newSchema = DimensionsSpec.convertSpatialSchema(spatialSchema);
      this.dimensions.add(newSchema);
      dimensionSchemaMap.put(newSchema.getName(), newSchema);
    }
    this.includeAllDimensions = includeAllDimensions;
    this.useNestedColumnIndexerForSchemaDiscovery =
        useNestedColumnIndexerForSchemaDiscovery != null && useNestedColumnIndexerForSchemaDiscovery;
  }

  @JsonProperty
  public List<DimensionSchema> getDimensions()
  {
    return dimensions;
  }

  @JsonProperty
  public Set<String> getDimensionExclusions()
  {
    return dimensionExclusions;
  }

  @JsonProperty
  public boolean isIncludeAllDimensions()
  {
    return includeAllDimensions;
  }

  @JsonProperty
  public boolean useNestedColumnIndexerForSchemaDiscovery()
  {
    return useNestedColumnIndexerForSchemaDiscovery;
  }

  @Deprecated
  @JsonIgnore
  public List<SpatialDimensionSchema> getSpatialDimensions()
  {
    Iterable<NewSpatialDimensionSchema> filteredList = Iterables.filter(dimensions, NewSpatialDimensionSchema.class);

    Iterable<SpatialDimensionSchema> transformedList = Iterables.transform(
        filteredList,
        new Function<NewSpatialDimensionSchema, SpatialDimensionSchema>()
        {
          @Nullable
          @Override
          public SpatialDimensionSchema apply(NewSpatialDimensionSchema input)
          {
            return new SpatialDimensionSchema(input.getName(), input.getDims());
          }
        }
    );

    return Lists.newArrayList(transformedList);
  }


  @JsonIgnore
  public List<String> getDimensionNames()
  {
    return Lists.transform(
        dimensions,
        new Function<DimensionSchema, String>()
        {
          @Override
          public String apply(DimensionSchema input)
          {
            return input.getName();
          }
        }
    );
  }

  @PublicApi
  public DimensionSchema getSchema(String dimension)
  {
    return dimensionSchemaMap.get(dimension);
  }

  public boolean hasCustomDimensions()
  {
    return !(dimensions == null || dimensions.isEmpty());
  }

  @PublicApi
  public DimensionsSpec withDimensions(List<DimensionSchema> dims)
  {
    return new DimensionsSpec(
        dims,
        ImmutableList.copyOf(dimensionExclusions),
        null,
        includeAllDimensions,
        useNestedColumnIndexerForSchemaDiscovery
    );
  }

  public DimensionsSpec withDimensionExclusions(Set<String> dimExs)
  {
    return new DimensionsSpec(
        dimensions,
        ImmutableList.copyOf(Sets.union(dimensionExclusions, dimExs)),
        null,
        includeAllDimensions,
        useNestedColumnIndexerForSchemaDiscovery
    );
  }

  @Deprecated
  public DimensionsSpec withSpatialDimensions(List<SpatialDimensionSchema> spatials)
  {
    return new DimensionsSpec(
        dimensions,
        ImmutableList.copyOf(dimensionExclusions),
        spatials,
        includeAllDimensions,
        useNestedColumnIndexerForSchemaDiscovery
    );
  }

  private void verify(List<SpatialDimensionSchema> spatialDimensions)
  {
    List<String> dimNames = getDimensionNames();
    Preconditions.checkArgument(
        Sets.intersection(this.dimensionExclusions, Sets.newHashSet(dimNames)).isEmpty(),
        "dimensions and dimensions exclusions cannot overlap"
    );

    List<String> spatialDimNames = Lists.transform(
        spatialDimensions,
        new Function<SpatialDimensionSchema, String>()
        {
          @Override
          public String apply(SpatialDimensionSchema input)
          {
            return input.getDimName();
          }
        }
    );

    // Don't allow duplicates between main list and deprecated spatial list
    ParserUtils.validateFields(Iterables.concat(dimNames, spatialDimNames));
    ParserUtils.validateFields(dimensionExclusions);
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DimensionsSpec that = (DimensionsSpec) o;
    return includeAllDimensions == that.includeAllDimensions
           && useNestedColumnIndexerForSchemaDiscovery == that.useNestedColumnIndexerForSchemaDiscovery
           && Objects.equals(dimensions, that.dimensions)
           && Objects.equals(dimensionExclusions, that.dimensionExclusions);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(
        dimensions,
        dimensionExclusions,
        includeAllDimensions,
        useNestedColumnIndexerForSchemaDiscovery
    );
  }

  @Override
  public String toString()
  {
    return "DimensionsSpec{" +
           "dimensions=" + dimensions +
           ", dimensionExclusions=" + dimensionExclusions +
           ", includeAllDimensions=" + includeAllDimensions +
           ", useNestedColumnIndexerForSchemaDiscovery=" + useNestedColumnIndexerForSchemaDiscovery +
           '}';
  }

  public static final class Builder
  {
    private List<DimensionSchema> dimensions;
    private List<String> dimensionExclusions;
    private List<SpatialDimensionSchema> spatialDimensions;
    private boolean includeAllDimensions;

    private boolean useNestedColumnIndexerForSchemaDiscovery;

    public Builder setDimensions(List<DimensionSchema> dimensions)
    {
      this.dimensions = dimensions;
      return this;
    }

    public Builder setDefaultSchemaDimensions(List<String> dimensions)
    {
      this.dimensions = getDefaultSchemas(dimensions);
      return this;
    }

    public Builder setDimensionExclusions(List<String> dimensionExclusions)
    {
      this.dimensionExclusions = dimensionExclusions;
      return this;
    }

    @Deprecated
    public Builder setSpatialDimensions(List<SpatialDimensionSchema> spatialDimensions)
    {
      this.spatialDimensions = spatialDimensions;
      return this;
    }

    public Builder setIncludeAllDimensions(boolean includeAllDimensions)
    {
      this.includeAllDimensions = includeAllDimensions;
      return this;
    }

    public Builder setUseNestedColumnIndexerForSchemaDiscovery(boolean useNestedColumnIndexerForSchemaDiscovery)
    {
      this.useNestedColumnIndexerForSchemaDiscovery = useNestedColumnIndexerForSchemaDiscovery;
      return this;
    }

    public DimensionsSpec build()
    {
      return new DimensionsSpec(
          dimensions,
          dimensionExclusions,
          spatialDimensions,
          includeAllDimensions,
          useNestedColumnIndexerForSchemaDiscovery
      );
    }
  }
}
