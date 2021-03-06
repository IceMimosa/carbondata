/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.spark.load

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.{TaskAttemptID, TaskType}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.execution.datasources.{FilePartition, FileScanRDD, PartitionedFile}
import org.apache.spark.sql.hive.DistributionUtil
import org.apache.spark.sql.util.SparkSQLUtil
import org.apache.spark.sql.util.SparkSQLUtil.sessionState

import org.apache.carbondata.common.logging.LogServiceFactory
import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.datastore.block.{Distributable, TableBlockInfo}
import org.apache.carbondata.core.datastore.impl.FileFactory
import org.apache.carbondata.core.metadata.ColumnarFormatVersion
import org.apache.carbondata.core.util.{CarbonProperties, ThreadLocalSessionInfo}
import org.apache.carbondata.hadoop.util.CarbonInputFormatUtil
import org.apache.carbondata.processing.loading.csvinput.{BlockDetails, CSVInputFormat}
import org.apache.carbondata.processing.loading.model.CarbonLoadModel
import org.apache.carbondata.processing.util.CarbonLoaderUtil
import org.apache.carbondata.spark.adapter.CarbonToSparkAdapter
import org.apache.carbondata.spark.rdd.CarbonDataRDDFactory.{getNodeBlockMapping, LOGGER}
import org.apache.carbondata.spark.util.{CarbonSparkUtil, CommonUtil}

object CsvRDDHelper {

  private val LOGGER = LogServiceFactory.getLogService(this.getClass.getCanonicalName)

  /**
   * create a RDD that does reading of multiple CSV files
   */
  def csvFileScanRDD(
      spark: SparkSession,
      model: CarbonLoadModel,
      hadoopConf: Configuration
  ): RDD[InternalRow] = {
    // 1. partition
    val defaultMaxSplitBytes = sessionState(spark).conf.filesMaxPartitionBytes
    val openCostInBytes = sessionState(spark).conf.filesOpenCostInBytes
    val defaultParallelism = spark.sparkContext.defaultParallelism
    CommonUtil.configureCSVInputFormat(hadoopConf, model)
    hadoopConf.set(FileInputFormat.INPUT_DIR, model.getFactFilePath)
    val jobContext = CarbonSparkUtil.createHadoopJob(hadoopConf)
    val inputFormat = new CSVInputFormat()
    val rawSplits = inputFormat.getSplits(jobContext).toArray
    var totalLength = 0L
    val splitFiles = rawSplits.map { split =>
      val fileSplit = split.asInstanceOf[FileSplit]
      totalLength = totalLength + fileSplit.getLength
      PartitionedFile(
        InternalRow.empty,
        fileSplit.getPath.toString,
        fileSplit.getStart,
        fileSplit.getLength,
        fileSplit.getLocations)
    }.sortBy(_.length)(implicitly[Ordering[Long]].reverse)
    model.setTotalSize(totalLength)
    val totalBytes = splitFiles.map(_.length + openCostInBytes).sum
    val bytesPerCore = totalBytes / defaultParallelism

    val maxSplitBytes = Math.min(defaultMaxSplitBytes, Math.max(openCostInBytes, bytesPerCore))
    LOGGER.info(s"Planning scan with bin packing, max size: $maxSplitBytes bytes, " +
                s"open cost is considered as scanning $openCostInBytes bytes.")

    val partitions = new ArrayBuffer[FilePartition]
    val currentFiles = new ArrayBuffer[PartitionedFile]
    var currentSize = 0L

    def closePartition(): Unit = {
      if (currentFiles.nonEmpty) {
        val newPartition =
          CarbonToSparkAdapter.createFilePartition(
            partitions.size,
            currentFiles)
        partitions += newPartition
      }
      currentFiles.clear()
      currentSize = 0
    }

    splitFiles.foreach { file =>
      if (currentSize + file.length > maxSplitBytes) {
        closePartition()
      }
      // Add the given file to the current partition.
      currentSize += file.length + openCostInBytes
      currentFiles += file
    }
    closePartition()

    // 2. read function
    val readFunction = getReadFunction(hadoopConf)
    new FileScanRDD(spark, readFunction, partitions)
  }

  /**
   * create a RDD that does reading of multiple CSV files based on data locality
   */
  def csvFileScanRDDForLocalSort(
      spark: SparkSession,
      model: CarbonLoadModel,
      hadoopConf: Configuration
  ): RDD[InternalRow] = {
    CommonUtil.configureCSVInputFormat(hadoopConf, model)
    // divide the blocks among the nodes as per the data locality
    val nodeBlockMapping = getNodeBlockMapping(spark.sqlContext, hadoopConf, model)
    val partitions = new ArrayBuffer[FilePartition]
    // create file partition
    nodeBlockMapping.map { entry =>
      val files = entry._2.asScala.map(distributable => {
        val tableBlock = distributable.asInstanceOf[TableBlockInfo]
        PartitionedFile(
          InternalRow.empty,
          tableBlock.getFilePath,
          tableBlock.getBlockOffset,
          tableBlock.getBlockLength,
          tableBlock.getLocations)
      }).toArray
      val newPartition =
        CarbonToSparkAdapter.createFilePartition(
          partitions.size,
          collection.mutable.ArrayBuffer(files: _*))
      partitions += newPartition
    }

    // 2. read function
    val readFunction = getReadFunction(hadoopConf)
    new FileScanRDD(spark, readFunction, partitions)
  }

  private def getReadFunction(configuration: Configuration): (PartitionedFile =>
    Iterator[InternalRow]) = {
    val serializableConfiguration = SparkSQLUtil.getSerializableConfigurableInstance(configuration)
    new (PartitionedFile => Iterator[InternalRow]) with Serializable {
      override def apply(file: PartitionedFile): Iterator[InternalRow] = {
        new Iterator[InternalRow] {
          ThreadLocalSessionInfo.setConfigurationToCurrentThread(serializableConfiguration.value)
          val jobTrackerId = CarbonInputFormatUtil.createJobTrackerID()
          val attemptId = new TaskAttemptID(jobTrackerId, 0, TaskType.MAP, 0, 0)
          val hadoopAttemptContext = new TaskAttemptContextImpl(FileFactory.getConfiguration,
            attemptId)
          val inputSplit =
            new FileSplit(new Path(file.filePath), file.start, file.length, file.locations)
          var finished = false
          val inputFormat = new CSVInputFormat()
          val reader = inputFormat.createRecordReader(inputSplit, hadoopAttemptContext)
          reader.initialize(inputSplit, hadoopAttemptContext)

          override def hasNext: Boolean = {
            if (!finished) {
              if (reader != null) {
                if (reader.nextKeyValue()) {
                  true
                } else {
                  finished = true
                  reader.close()
                  false
                }
              } else {
                finished = true
                false
              }
            } else {
              false
            }
          }

          override def next(): InternalRow = {
            new GenericInternalRow(reader.getCurrentValue.get().asInstanceOf[Array[Any]])
          }
        }
      }
    }
  }

}
