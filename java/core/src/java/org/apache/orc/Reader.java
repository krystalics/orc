/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;

/**
 * The interface for reading ORC files.
 *
 * One Reader can support multiple concurrent RecordReader.
 */
public interface Reader extends Closeable {

  /**
   * Get the number of rows in the file.
   * @return the number of rows
   */
  long getNumberOfRows();

  /**
   * Get the deserialized data size of the file
   * @return raw data size
   */
  long getRawDataSize();

  /**
   * Get the deserialized data size of the specified columns
   * @param colNames the list of column names
   * @return raw data size of columns
   */
  long getRawDataSizeOfColumns(List<String> colNames);

  /**
   * Get the deserialized data size of the specified columns ids
   * @param colIds - internal column id (check orcfiledump for column ids)
   * @return raw data size of columns
   */
  long getRawDataSizeFromColIndices(List<Integer> colIds);

  /**
   * Get the user metadata keys.
   * @return the set of metadata keys
   */
  List<String> getMetadataKeys();

  /**
   * Get a user metadata value.
   * @param key a key given by the user
   * @return the bytes associated with the given key
   */
  ByteBuffer getMetadataValue(String key);

  /**
   * Did the user set the given metadata value.
   * @param key the key to check
   * @return true if the metadata value was set
   */
  boolean hasMetadataValue(String key);

  /**
   * Get the compression kind.
   * @return the kind of compression in the file
   */
  CompressionKind getCompressionKind();

  /**
   * Get the buffer size for the compression.
   * @return number of bytes to buffer for the compression codec.
   */
  int getCompressionSize();

  /**
   * Get the number of rows per a entry in the row index.
   * @return the number of rows per an entry in the row index or 0 if there
   * is no row index.
   */
  int getRowIndexStride();

  /**
   * Get the list of stripes.
   * @return the information about the stripes in order
   */
  List<StripeInformation> getStripes();

  /**
   * Get the length of the file.
   * @return the number of bytes in the file
   */
  long getContentLength();

  /**
   * Get the statistics about the columns in the file.
   * @return the information about the column
   */
  ColumnStatistics[] getStatistics();

  /**
   * Get the type of rows in this ORC file.
   */
  TypeDescription getSchema();

  /**
   * Get the list of types contained in the file. The root type is the first
   * type in the list.
   * @return the list of flattened types
   * @deprecated use getSchema instead
   */
  List<OrcProto.Type> getTypes();

  /**
   * Get the file format version.
   */
  OrcFile.Version getFileVersion();

  /**
   * Get the version of the writer of this file.
   */
  OrcFile.WriterVersion getWriterVersion();

  /**
   * Get the file tail (footer + postscript)
   *
   * @return - file tail
   */
  OrcProto.FileTail getFileTail();

  /**
   * Get the list of encryption keys for column encryption.
   * @return the set of encryption keys
   */
  EncryptionKey[] getColumnEncryptionKeys();

  /**
   * Get the data masks for the unencrypted variant of the data.
   * @return the lists of data masks
   */
  DataMaskDescription[] getDataMasks();

  /**
   * Get the list of encryption variants for the data.
   */
  EncryptionVariant[] getEncryptionVariants();

  /**
   * Get the stripe statistics for a given variant. The StripeStatistics will
   * have 1 entry for each column in the variant. This enables the user to
   * get the stripe statistics for each variant regardless of which keys are
   * available.
   * @param variant the encryption variant or null for unencrypted
   * @return a list of stripe statistics (one per a stripe)
   * @throws IOException if the required key is not available
   */
  List<StripeStatistics> getVariantStripeStatistics(EncryptionVariant variant
                                                    ) throws IOException;

  /**
   * Options for creating a RecordReader.
   */
  class Options implements Cloneable {
    private boolean[] include;
    private long offset = 0;
    private long length = Long.MAX_VALUE;
    private int positionalEvolutionLevel;
    private SearchArgument sarg = null;
    private String[] columnNames = null;
    private Boolean useZeroCopy = null;
    private Boolean skipCorruptRecords = null;
    private TypeDescription schema = null;
    private String[] preFilterColumns = null;
    Consumer<OrcFilterContext> skipRowCallback = null;
    private DataReader dataReader = null;
    private Boolean tolerateMissingSchema = null;
    private boolean forcePositionalEvolution;
    private boolean isSchemaEvolutionCaseAware =
        (boolean) OrcConf.IS_SCHEMA_EVOLUTION_CASE_SENSITIVE.getDefaultValue();
    private boolean includeAcidColumns = true;
    private boolean allowSARGToFilter = false;
    private boolean useSelected = false;

    public Options() {
      // PASS
    }

    public Options(Configuration conf) {
      useZeroCopy = OrcConf.USE_ZEROCOPY.getBoolean(conf);
      skipCorruptRecords = OrcConf.SKIP_CORRUPT_DATA.getBoolean(conf);
      tolerateMissingSchema = OrcConf.TOLERATE_MISSING_SCHEMA.getBoolean(conf);
      forcePositionalEvolution = OrcConf.FORCE_POSITIONAL_EVOLUTION.getBoolean(conf);
      positionalEvolutionLevel = OrcConf.FORCE_POSITIONAL_EVOLUTION_LEVEL.getInt(conf);
      isSchemaEvolutionCaseAware =
          OrcConf.IS_SCHEMA_EVOLUTION_CASE_SENSITIVE.getBoolean(conf);
      allowSARGToFilter = OrcConf.ALLOW_SARG_TO_FILTER.getBoolean(conf);
      useSelected = OrcConf.READER_USE_SELECTED.getBoolean(conf);
    }

    /**
     * Set the list of columns to read.
     * @param include a list of columns to read
     * @return this
     */
    public Options include(boolean[] include) {
      this.include = include;
      return this;
    }

    /**
     * Set the range of bytes to read
     * @param offset the starting byte offset
     * @param length the number of bytes to read
     * @return this
     */
    public Options range(long offset, long length) {
      this.offset = offset;
      this.length = length;
      return this;
    }

    /**
     * Set the schema on read type description.
     */
    public Options schema(TypeDescription schema) {
      this.schema = schema;
      return this;
    }

    /**
     * Set a row level filter.
     * This is an advanced feature that allows the caller to specify
     * a list of columns that are read first and then a filter that
     * is called to determine which rows if any should be read.
     *
     * User should expect the batches that come from the reader
     * to use the selected array set by their filter.
     *
     * Use cases for this are predicates that SearchArgs can't represent,
     * such as relationships between columns (eg. columnA == columnB).
     * @param filterColumnNames a comma separated list of the column names that
     *                      are read before the filter is applied. Only top
     *                      level columns in the reader's schema can be used
     *                      here and they must not be duplicated.
     * @param filterCallback a function callback to perform filtering during the call to
     *              RecordReader.nextBatch. This function should not reference
     *               any static fields nor modify the passed in ColumnVectors but
     *               should set the filter output using the selected array.
     *
     * @return this
     */
    public Options setRowFilter(String[] filterColumnNames, Consumer<OrcFilterContext> filterCallback) {
      this.preFilterColumns = filterColumnNames;
      this.skipRowCallback =  filterCallback;
      return this;
    }

    /**
     * Set search argument for predicate push down.
     * @param sarg the search argument
     * @param columnNames the column names for
     * @return this
     */
    public Options searchArgument(SearchArgument sarg, String[] columnNames) {
      this.sarg = sarg;
      this.columnNames = columnNames;
      return this;
    }

    public Options allowSARGToFilter(boolean allowSARGToFilter) {
      this.allowSARGToFilter = allowSARGToFilter;
      return this;
    }

    public boolean isAllowSARGToFilter() {
      return allowSARGToFilter;
    }

    /**
     * Set whether to use zero copy from HDFS.
     * @param value the new zero copy flag
     * @return this
     */
    public Options useZeroCopy(boolean value) {
      this.useZeroCopy = value;
      return this;
    }

    public Options dataReader(DataReader value) {
      this.dataReader = value;
      return this;
    }

    /**
     * Set whether to skip corrupt records.
     * @param value the new skip corrupt records flag
     * @return this
     */
    public Options skipCorruptRecords(boolean value) {
      this.skipCorruptRecords = value;
      return this;
    }

    /**
     * Set whether to make a best effort to tolerate schema evolution for files
     * which do not have an embedded schema because they were written with a'
     * pre-HIVE-4243 writer.
     * @param value the new tolerance flag
     * @return this
     */
    public Options tolerateMissingSchema(boolean value) {
      this.tolerateMissingSchema = value;
      return this;
    }

    /**
     * Set whether to force schema evolution to be positional instead of
     * based on the column names.
     * @param value force positional evolution
     * @return this
     */
    public Options forcePositionalEvolution(boolean value) {
      this.forcePositionalEvolution = value;
      return this;
    }

    /**
     * Set number of levels to force schema evolution to be positional instead of
     * based on the column names.
     * @param value number of levels of positional schema evolution
     * @return this
     */
    public Options positionalEvolutionLevel(int value) {
      this.positionalEvolutionLevel = value;
      return this;
    }


    /**
     * Set boolean flag to determine if the comparision of field names in schema
     * evolution is case sensitive
     * @param value the flag for schema evolution is case sensitive or not.
     * @return this
     */
    public Options isSchemaEvolutionCaseAware(boolean value) {
      this.isSchemaEvolutionCaseAware = value;
      return this;
    }
    /**
     * {@code true} if acid metadata columns should be decoded otherwise they will
     * be set to {@code null}.
     */
    public Options includeAcidColumns(boolean includeAcidColumns) {
      this.includeAcidColumns = includeAcidColumns;
      return this;
    }

    public boolean[] getInclude() {
      return include;
    }

    public long getOffset() {
      return offset;
    }

    public long getLength() {
      return length;
    }

    public TypeDescription getSchema() {
      return schema;
    }

    public SearchArgument getSearchArgument() {
      return sarg;
    }

    public Consumer<OrcFilterContext> getFilterCallback() {
      return skipRowCallback;
    }

    public String[] getPreFilterColumnNames(){
      return preFilterColumns;
    }

    public String[] getColumnNames() {
      return columnNames;
    }

    public long getMaxOffset() {
      long result = offset + length;
      if (result < 0) {
        result = Long.MAX_VALUE;
      }
      return result;
    }

    public Boolean getUseZeroCopy() {
      return useZeroCopy;
    }

    public Boolean getSkipCorruptRecords() {
      return skipCorruptRecords;
    }

    public DataReader getDataReader() {
      return dataReader;
    }

    public boolean getForcePositionalEvolution() {
      return forcePositionalEvolution;
    }

    public int getPositionalEvolutionLevel() {
      return positionalEvolutionLevel;
    }

    public boolean getIsSchemaEvolutionCaseAware() {
      return isSchemaEvolutionCaseAware;
    }

    public boolean getIncludeAcidColumns() {
      return includeAcidColumns;
    }

    @Override
    public Options clone() {
      try {
        Options result = (Options) super.clone();
        if (dataReader != null) {
          result.dataReader = dataReader.clone();
        }
        return result;
      } catch (CloneNotSupportedException e) {
        throw new UnsupportedOperationException("uncloneable", e);
      }
    }

    @Override
    public String toString() {
      StringBuilder buffer = new StringBuilder();
      buffer.append("{include: ");
      if (include == null) {
        buffer.append("null");
      } else {
        buffer.append("[");
        for(int i=0; i < include.length; ++i) {
          if (i != 0) {
            buffer.append(", ");
          }
          buffer.append(include[i]);
        }
        buffer.append("]");
      }
      buffer.append(", offset: ");
      buffer.append(offset);
      buffer.append(", length: ");
      buffer.append(length);
      if (sarg != null) {
        buffer.append(", sarg: ");
        buffer.append(sarg.toString());
      }
      if (schema != null) {
        buffer.append(", schema: ");
        schema.printToBuffer(buffer);
      }
      buffer.append(", includeAcidColumns: ").append(includeAcidColumns);
      buffer.append(", allowSARGToFilter: ").append(allowSARGToFilter);
      buffer.append(", useSelected: ").append(useSelected);
      buffer.append("}");
      return buffer.toString();
    }

    public boolean getTolerateMissingSchema() {
      return tolerateMissingSchema != null ? tolerateMissingSchema :
          (Boolean) OrcConf.TOLERATE_MISSING_SCHEMA.getDefaultValue();
    }

    public boolean useSelected() {
      return useSelected;
    }

    public Options useSelected(boolean newValue) {
      this.useSelected = newValue;
      return this;
    }
  }

  /**
   * Create a default options object that can be customized for creating
   * a RecordReader.
   * @return a new default Options object
   */
  Options options();

  /**
   * Create a RecordReader that reads everything with the default options.
   * @return a new RecordReader
   */
  RecordReader rows() throws IOException;

  /**
   * Create a RecordReader that uses the options given.
   * This method can't be named rows, because many callers used rows(null)
   * before the rows() method was introduced.
   * @param options the options to read with
   * @return a new RecordReader
   */
  RecordReader rows(Options options) throws IOException;

  /**
   * @return List of integers representing version of the file, in order from major to minor.
   */
  List<Integer> getVersionList();

  /**
   * @return Gets the size of metadata, in bytes.
   */
  int getMetadataSize();

  /**
   * @return Stripe statistics, in original protobuf form.
   * @deprecated Use {@link #getStripeStatistics()} instead.
   */
  List<OrcProto.StripeStatistics> getOrcProtoStripeStatistics();

  /**
   * Get the stripe statistics for all of the columns.
   * @return a list of the statistics for each stripe in the file
   */
  List<StripeStatistics> getStripeStatistics() throws IOException;

  /**
   * Get the stripe statistics from the file.
   * @param include null for all columns or an array where the required columns
   *                are selected
   * @return a list of the statistics for each stripe in the file
   */
  List<StripeStatistics> getStripeStatistics(boolean[] include) throws IOException;

  /**
   * @return File statistics, in original protobuf form.
   * @deprecated Use {@link #getStatistics()} instead.
   */
  List<OrcProto.ColumnStatistics> getOrcProtoFileStatistics();

  /**
   * @return Serialized file metadata read from disk for the purposes of caching, etc.
   */
  ByteBuffer getSerializedFileFooter();

  /**
   * Was the file written using the proleptic Gregorian calendar.
   */
  boolean writerUsedProlepticGregorian();

  /**
   * Should the returned values use the proleptic Gregorian calendar?
   */
  boolean getConvertToProlepticGregorian();
}
