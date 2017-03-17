/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.utils

import com.intel.analytics.bigdl.optim.Trigger
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.visualization.tensorboard.{FileReader, FileWriter}

import scala.collection.mutable
import scala.reflect.ClassTag

/**
 * Logger for tensorboard.
 * Support scalar and histogram now.
 * @param logDir
 * @param appName
 */
abstract class Summary(
      logDir: String,
      appName: String) {
  protected val writer: FileWriter

  /**
   * Add a scalar summary.
   * @param tag tag name.
   * @param value tag value.
   * @param step current step.
   * @return this
   */
  def addScalar(
        tag: String,
        value: Float,
        step: Long): this.type = {
    writer.addSummary(
      Summary.scalar(tag, value), step
    )
    this
  }

  /**
   * Add a histogram summary.
   * @param tag tag name.
   * @param value a tensor.
   * @param step current step.
   * @return this
   */
  def addHistogram[T: ClassTag](
        tag: String,
        value: Tensor[T],
        step: Long)(implicit ev: TensorNumeric[T]): this.type = {
    writer.addSummary(
      Summary.histogram[T](tag, value), step
    )
    this
  }

  /**
   * Read scalar values to an array of triple by tag name.
   * First element of the triple is step, second is value, third is wallclocktime.
   * @param tag tag name.
   * @return an array of triple.
   */
  def readScalar(tag: String): Array[(Long, Float, Double)]
}

/**
 * Train logger for tensorboard.
 * Use optimize.setTrainSummary to enable train logger. Then the log will be saved to
 * logDir/appName/train.
 * @param logDir log dir.
 * @param appName application Name.
 */
class TrainSummary(
      logDir: String,
      appName: String) extends Summary(logDir, appName) {
  protected val folder = s"$logDir/$appName/train"
  protected override val writer = new FileWriter(folder)
  private val triggers: mutable.HashMap[String, Trigger] = mutable.HashMap(
        "learningRate" -> Trigger.severalIteration(1),
        "loss" -> Trigger.severalIteration(1),
        "throughput" -> Trigger.severalIteration(1))

  /**
   * Read scalar values to an array of triple by tag name.
   * First element of the triple is step, second is value, third is wallClockTime.
   * @param tag tag name. Supported tag names is "learningRate", "Loss", "throughput"
   * @return an array of triple.
   */
  override def readScalar(tag: String): Array[(Long, Float, Double)] = {
    FileReader.readScalar(folder, tag)
  }

  /**
   * Supported tag name are learningRate, loss, throughput, parameters.
   * Parameters contains weight, bias, gradWeight, gradBias, and some running status(eg.
   * runningMean and runningVar in BatchNormalization).
   *
   * Notice: By default, we record learningRate, loss and throughput each iteration, while
   * recording parameters is disabled. The reason is getting parameters from workers is a
   * heavy operation when the model is very big.
   *
   * @param tag tag name
   * @param trigger trigger
   * @return
   */
  def setSummaryTrigger(tag: String, trigger: Trigger): this.type = {
    require(tag.equals("learningRate") || tag.equals("Loss") ||
      tag.equals("throughput") | tag.equals("parameters"),
      s"TrainSummary: only support learningRate, Loss, parameters and throughput")
    triggers(tag) = trigger
    this
  }

  /**
   * Get a trigger by tag name.
   * @param tag
   * @return
   */
  def getSummaryTrigger(tag: String): Option[Trigger] = {
    if (triggers.contains(tag)) {
      Some(triggers(tag))
    } else {
      None
    }
  }

  private[bigdl] def getScalarTriggers(): mutable.HashMap[String, Trigger] = {
    triggers.filter(!_._1.equals("parameters"))
  }
}

object TrainSummary{
  def apply(logDir: String,
      appName: String): TrainSummary = {
    new TrainSummary(logDir, appName)
  }
}

/**
 * Validation logger for tensorboard.
 * Use optimize.setValidation to enable validation logger. Then the log will be saved to
 * logDir/appName/Validation.
 * @param logDir
 * @param appName
 */
class ValidationSummary(
      logDir: String,
      appName: String) extends Summary(logDir, appName) {
  protected val folder = s"$logDir/$appName/validation"
  protected override val writer = new FileWriter(folder)

  /**
   * ReadScalar by tag name. Optional tag name is based on ValidationMethod, "Loss",
   * "Top1Accuracy" or "Top5Accuracy".
   * @param tag tag name.
   * @return an array of triple.
   */
  override def readScalar(tag: String): Array[(Long, Float, Double)] = {
    FileReader.readScalar(folder, tag)
  }
}

object ValidationSummary{
  def apply(logDir: String,
      appName: String): ValidationSummary = {
    new ValidationSummary(logDir, appName)
  }
}

object Summary {

  /**
   * Create a scalar summary.
   * @param tag tag name
   * @param scalar scalar value
   * @return
   */
  def scalar(tag: String, scalar : Float): org.tensorflow.framework.Summary = {
    val v = org.tensorflow.framework.Summary.Value.newBuilder().setTag(tag).setSimpleValue(scalar)
    org.tensorflow.framework.Summary.newBuilder().addValue(v).build()
  }

  private val limits = makeHistogramBuckets()

  /**
   * Create a histogram summary.
   * @param tag tag name.
   * @param values values.
   * @return
   */
  def histogram[T: ClassTag](
      tag: String,
      values: Tensor[T])(implicit ev: TensorNumeric[T]): org.tensorflow.framework.Summary = {
    val counts = new Array[Int](limits.length)

    var squares = 0.0
    values.apply1{value =>
      val v = ev.toType[Double](value)
      squares += v * v
      val index = bisectLeft(limits, v)
      counts(index) += 1
      value
    }

    val histogram = org.tensorflow.framework.HistogramProto.newBuilder()
      .setMin(ev.toType[Double](values.min()))
      .setMax(ev.toType[Double](values.max()))
      .setNum(values.nElement())
      .setSum(ev.toType[Double](values.sum()))
      .setSumSquares(squares)

    var i = 0
    while (i < counts.length) {
      if (counts(i) != 0) {
        histogram.addBucket(counts(i))
        histogram.addBucketLimit(limits(i))
      }
      i += 1
    }
    val v = org.tensorflow.framework.Summary.Value.newBuilder().setTag(tag).setHisto(histogram)
    org.tensorflow.framework.Summary.newBuilder().addValue(v).build()
  }

  /**
   * Find a bucket for x.
   */
  private def bisectLeft(
      a: Array[Double],
      x: Double,
      lo: Int = 0,
      hi: Int = -1): Int = {
    require(lo >= 0)
    var high = if (hi == -1) {
      a.length
    } else {
      hi
    }
    var low = lo

    while (low < high) {
      val mid = (low + high) / 2
      if (a(mid) < x) {
        low = mid + 1
      } else {
        high = mid
      }
    }
    low
  }

  /**
   * Create a histogram buckets.
   * @return
   */
  private def makeHistogramBuckets(): Array[Double] = {
    var v = 1e-12
    val buckets = new Array[Double](1549)
    var i = 1
    buckets(774) = 0.0
    while (i <= 774) {
      buckets(774 + i) = v
      buckets(774 - i) = -v
      v *= 1.1
      i += 1
    }
    buckets
  }

}


