/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.utils.stages

import com.salesforce.op.features.types.{NameStats, Text}
import com.salesforce.op.stages.impl.feature.TextTokenizer
import com.salesforce.op.utils.json.{JsonLike, JsonUtils}
import com.twitter.algebird._
import com.twitter.algebird.Operators._
import com.twitter.algebird.macros.caseclass._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging

import scala.io.Source
import scala.util.Try
import scala.util.matching.Regex

/**
 * Provides shared helper functions and variables (namely, broadcast dictionaries) for name identification
 * and name to gender transformation.
 * @tparam T     the FeatureType (subtype of Text) to operate over
 */
private[op] trait NameDetectFun[T <: Text] extends Logging {
  import com.salesforce.op.utils.stages.NameDetectUtils._

  def preProcess(input: T#Value): Seq[String] = {
    TextTokenizer.tokenize(Text(input)).tokens.toArray
  }

  def computeGuardCheckQuantities(
    input: T#Value,
    tokens: Seq[String],
    hllMonoid: HyperLogLogMonoid
  ): GuardCheckStats = {
    // TODO: Make params out of these numbers
    val textLength = input.getOrElse("").length
    GuardCheckStats(
      countBelowMaxNumTokens = if (tokens.length < 10) 1 else 0,
      countAboveMinCharLength = if (textLength > 2) 1 else 0,
      approxMomentsOfTextLength = Moments(textLength),
      approxNumUnique = hllMonoid.create(input.getOrElse("").getBytes)
    )
  }

  def performGuardChecks(stats: GuardCheckStats, hllMonoid: HyperLogLogMonoid): Boolean = {
    val N: Double = stats.approxMomentsOfTextLength.count.toDouble
    val checks = List(
      // check that in at least 3/4 of the texts there are no more than 10 tokens
      (stats.countBelowMaxNumTokens / N) > 0.75,
      // check that at least 3/4 of the texts are longer than 3 characters
      (stats.countAboveMinCharLength / N) > 0.75,
      // check that the standard deviation of the text length is greater than a small number
      N < 10 || stats.approxMomentsOfTextLength.stddev > 0.05,
      // check that the number of unique entries is at least 10
      N < 100 || hllMonoid.sizeOf(stats.approxNumUnique).estimate > 10
    )
    // TODO: Delete this
    println(checks)
    checks.forall(identity)
  }

  def dictCheck(tokens: Seq[String], dict: Broadcast[NameDictionary]): Double = {
    tokens.map({ token: String => if (dict.value.value contains token) 1 else 0}).sum.toDouble / tokens.length
  }

  def getNameFromCustomIndex(tokens: Seq[String], index: Int): String = {
    if (tokens.isEmpty) ""
    else if (tokens.length == 1) tokens.head
    else {
      // Mod to accept -1 as valid index
      tokens((index + tokens.length) % tokens.length)
    }
  }

  import NameStats.GenderStrings._
  def identifyGenderByIndex(tokens: Seq[String], index: Int, dict: Broadcast[GenderDictionary]): String = {
    val nameToCheckGenderOf = getNameFromCustomIndex(tokens, index)
    dict.value.value.get(nameToCheckGenderOf).map(
      probMale => if (probMale >= 0.5) Male else Female
    ).getOrElse(GenderNA)
  }

  def identifyGender(
    input: T#Value,
    tokens: Seq[String],
    strategy: GenderDetectStrategy,
    genderDict: Broadcast[GenderDictionary]
  ): String = {
    import GenderDetectStrategy._
    strategy match {
      case FindHonorific() =>
        val matched = tokens filter { NameDetectUtils.AllHonorifics contains }
        if (matched.length == 1) {
          if (MaleHonorifics contains matched.head) Male else Female
        }
        else GenderNA
      case ByIndex(index) =>
        val nameToCheckGenderOf = getNameFromCustomIndex(tokens, index)
        genderDict.value.value.get(nameToCheckGenderOf).map(
          probMale => if (probMale >= 0.5) Male else Female
        ).getOrElse(GenderNA)
      case ByRegex(pattern) =>
        input.getOrElse("") match {
          case pattern(nameToCheckGenderOf) =>
            genderDict.value.value.get(preProcess(Some(nameToCheckGenderOf)).headOption.getOrElse("")).map(
              probMale => if (probMale >= 0.5) Male else Female
            ).getOrElse(GenderNA)
          case _ => GenderNA
        }
      case _ =>
        logError("Unimplemented gender detection strategy found")
        GenderNA
    }
  }

  def computeGenderResultsByStrategy(
    input: T#Value,
    tokens: Seq[String],
    genderDict: Broadcast[GenderDictionary]
  ): Map[String, GenderStats] = {
    GenderDetectStrategies map { strategy: GenderDetectStrategy =>
      val genderResult: String = identifyGender(input, tokens, strategy, genderDict)
      strategy.toString -> GenderStats(
        if (genderResult == Male) 1 else 0,
        if (genderResult == Female) 1 else 0,
        if (genderResult == GenderNA) 1 else 0
      )
    } toMap
  }

  def computeResults(
    input: T#Value,
    nameDict: Broadcast[NameDictionary],
    genderDict: Broadcast[GenderDictionary],
    hll: HyperLogLogMonoid
  ): NameDetectStats = {
    val tokens = preProcess(input)
    NameDetectStats(
      computeGuardCheckQuantities(input, tokens, hll),
      AveragedValue(1L, dictCheck(tokens, nameDict)),
      computeGenderResultsByStrategy(input, tokens, genderDict)
    )
  }
}

/**
 * Defines static values for name identification:
 * - Dictionary filenames and how to read them in
 * - Which parts of a string to check for first name (used in transforming from name to gender)
 *
 * Name and gender data are maintained by and taken from this repository:
 *  https://github.com/MWYang/InternationalNames
 * which itself sources data from:
 *  https://ec.europa.eu/jrc/en/language-technologies/jrc-names (currently unused)
 *  https://github.com/OpenGenderTracking/globalnamedata
 *  https://github.com/first20hours/google-10000-english
 */
private[op] object NameDetectUtils {
  case class NameDictionary
  (
    // Use the following line to use the smaller but less noisy gender dictionary as a source for names
    // value: Set[String] = GenderDictionary().value.keySet
    value: Set[String] = {
      val nameDictionary = collection.mutable.Set.empty[String]
      val dictionaryPath = "/Names_JRC_Combined.txt"
      val stream = getClass.getResourceAsStream(dictionaryPath)
      val buffer = Source.fromInputStream(stream)
      for {name <- buffer.getLines} {
        nameDictionary += name
      }
      buffer.close
      nameDictionary.toSet[String]
    }
  )

  case class GenderDictionary
  (
    value: Map[String, Double] = {
      val genderDictionary = collection.mutable.Map.empty[String, Double]
      val dictionaryPath = "/GenderDictionary_USandUK.csv"
      val stream = getClass.getResourceAsStream(dictionaryPath)
      val buffer = Source.fromInputStream(stream)
      // TODO: Also make use of frequency information in this dictionary
      for {row <- buffer.getLines.drop(1)} {
        val cols = row.split(",").map(_.trim)
        val name = cols(0).toLowerCase().replace("\\P{L}", "")
        val probMale = Try {
          cols(6).toDouble
        }.toOption
        probMale match {
          case Some(prob) => genderDictionary += (name -> prob)
          case None =>
        }
      }
      buffer.close
      genderDictionary.toMap[String, Double]
    }
  )

  val MaleHonorifics: Set[String] = Set("mr", "mister")
  val FemaleHonorifics: Set[String] = Set("ms", "mrs", "miss")
  val AllHonorifics: Set[String] = MaleHonorifics ++ FemaleHonorifics

  /**
   * Number of bits used for hashing in HyperLogLog (HLL). Error is about 1.04/sqrt(2^{bits}).
   * Default is 12 bits for 1% error which means each HLL instance is about 2^{12} = 4kb per instance.
   */
  val HLLBits = 12

  import GenderDetectStrategy._
  /**
   * The strategies to use for transforming name to gender; Order does not matter.
   *
   * The first RegEx pattern will extract all text after the first comma;
   * The second RegEx pattern will extract all text after both the first comma and the immediately next token,
   *   which accounts for patterns like `LastName, Honorific FirstName MiddleNames`
   */
  val GenderDetectStrategies: Seq[GenderDetectStrategy] = Seq(
    FindHonorific(), ByIndex(0), ByIndex(-1), ByRegex(""".*,(.*)""".r), ByRegex(""".*,\s+.*?\s+(.*)""".r)
  )
}

private[op] case class GuardCheckStats
(
  countBelowMaxNumTokens: Int = 0,
  countAboveMinCharLength: Int = 0,
  approxMomentsOfTextLength: Moments = Moments(0.0),
  approxNumUnique: HLL = new HyperLogLogMonoid(NameDetectUtils.HLLBits).zero
) extends JsonLike

private[op] case class GenderStats(numMale: Int = 0, numFemale: Int = 0, numOther: Int = 0) extends JsonLike

// TODO: Make proper documentation
// Defines the monoid accumulator for detecting names
private[op] case class NameDetectStats
(
  guardCheckQuantities: GuardCheckStats,
  dictCheckResult: AveragedValue,
  genderResultsByStrategy: Map[String, GenderStats]
) extends JsonLike {
  // TransmogrifAI's JsonUtils doesn't play nice with HLL
  override def toJson(pretty: Boolean): String = {
    val result: Map[String, Any] = Map(
      "guardCheckQuantities" -> Map(
        "countBelowMaxNumTokens" -> this.guardCheckQuantities.countBelowMaxNumTokens,
        "countAboveMinCharLength" -> this.guardCheckQuantities.countAboveMinCharLength,
        "approxMomentsOfTextLength" -> this.guardCheckQuantities.approxMomentsOfTextLength,
        "approxNumUnique" -> {
          val hllMonoid = new HyperLogLogMonoid(NameDetectUtils.HLLBits)
          hllMonoid.sizeOf(this.guardCheckQuantities.approxNumUnique).toString
        }
      ),
      "dictCheckResults" -> this.dictCheckResult,
      "genderResultsByStrategy" -> this.genderResultsByStrategy
    )
    JsonUtils.toJsonString(result, pretty = pretty)
  }
}
private[op] case object NameDetectStats {
  def monoid: Monoid[NameDetectStats] = new Monoid[NameDetectStats] {
    // Ideally, we could have avoided defining all of this
    // but Algebird's case class macro is not documented (at all) and spotty
    override def plus(l: NameDetectStats, r: NameDetectStats): NameDetectStats = NameDetectStats(
      GuardCheckStats(
        l.guardCheckQuantities.countBelowMaxNumTokens + r.guardCheckQuantities.countBelowMaxNumTokens,
        l.guardCheckQuantities.countAboveMinCharLength + r.guardCheckQuantities.countAboveMinCharLength,
        MomentsGroup.plus(
          l.guardCheckQuantities.approxMomentsOfTextLength,
          r.guardCheckQuantities.approxMomentsOfTextLength
        ),
        l.guardCheckQuantities.approxNumUnique + r.guardCheckQuantities.approxNumUnique
      ),
      l.dictCheckResult + r.dictCheckResult,
      l.genderResultsByStrategy + r.genderResultsByStrategy
    )
    override def zero: NameDetectStats = NameDetectStats.empty
  }

  def empty: NameDetectStats = NameDetectStats(GuardCheckStats(), AveragedValue(0L, 0), Map.empty[String, GenderStats])
}

import enumeratum._
private[op] sealed class GenderDetectStrategy extends EnumEntry
case object GenderDetectStrategy extends Enum[GenderDetectStrategy] {
  val values: Seq[GenderDetectStrategy] = findValues
  val delimiter = " WITH VALUE "
  case class ByIndex(index: Int) extends GenderDetectStrategy {
    override def toString: String = "ByIndex" + delimiter + index.toString
  }
  case class ByRegex(pattern: Regex) extends GenderDetectStrategy {
    override def toString: String = "ByRegex" + delimiter + pattern.toString
  }
  case class FindHonorific() extends GenderDetectStrategy {
    override def toString: String = "FindHonorific"
  }

  def fromString(s: String): GenderDetectStrategy = {
    val parts = s.split(delimiter)
    val entryName: String = parts(0)
    entryName match {
      case "ByIndex" => ByIndex(parts(1).toInt)
      case "ByRegex" => ByRegex(parts(1).r)
      case "FindHonorific" => FindHonorific()
    }
  }
}