package com.github.tashoyan.tfidf

import java.io.File

import org.apache.spark.ml.feature.RegexTokenizer
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.io.Source

object Main {
  private val spark = SparkSession.builder()
    .appName("TfIdf")
    .getOrCreate()
  spark.sparkContext.setLogLevel("WARN")

  import spark.implicits._

  private val docNameColumn = "doc_name"
  private val docPathColumn = "doc_path"
  private val rawTextColumn = "raw_text"
  private val wordsColumn = "words"

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      throw new IllegalArgumentException("Argument is expected: directory with documents database")
    }
    val docsDirPath = args.head

    val documents = readDocuments(docsDirPath)

    //    val documents = readDocuments(
    //      "battle_hymn_of_the_republic.txt",
    //      "seek_and_destroy.txt",
    //      "love_me_tender.txt"
    //    )

    val words = prepareWords(documents)
    words
      .select(wordsColumn)
      .show(false)

    val config = TfIdfConfig(documentColumn = wordsColumn)
    val tfIdf = new TfIdf(config)
    val terms = tfIdf.genTfIdf(words)

    val window = Window.partitionBy(config.docIdColumn)
      .orderBy(col(config.tfIdfColumn).desc)
    val rowNumColumn = "row_number"
    terms
      .withColumn(rowNumColumn, row_number() over window)
      .where(col(rowNumColumn) <= 20)
      .select(docNameColumn, config.tokenColumn, config.tfIdfColumn)
      .show(100, truncate = false)
  }

  private def readDocuments(docsDirPath: String): DataFrame = {
    val docsDir = new File(docsDirPath)
    if (!docsDir.isDirectory) {
      throw new IllegalArgumentException(s"Not a directory: $docsDirPath")
    }

    val docFiles = docsDir.listFiles()
      .filter(_.isFile)
    if (docFiles.isEmpty) {
      throw new IllegalArgumentException(s"None files found in the directory: $docsDirPath")
    }

    docFiles.map { docFile =>
      (docFile.getName, docFile.getAbsolutePath, Source.fromFile(docFile).mkString)
    }
      .toSeq
      .toDF(docNameColumn, docPathColumn, rawTextColumn)
  }

  private def prepareWords(rawText: DataFrame): DataFrame = {
    val noAbbrColumn = "no_abbr"
    val noAbbrText = rawText.withColumn(noAbbrColumn,
      regexp_replace(col(rawTextColumn), """\w+'ll""", ""))

    val noPunctColumn = "no_punct"
    val noPunctText = noAbbrText.withColumn(noPunctColumn,
      regexp_replace(col(noAbbrColumn), """[\p{Punct}]""", ""))

    val tokenizer: RegexTokenizer = new RegexTokenizer()
      .setInputCol(noPunctColumn)
      .setOutputCol(wordsColumn)
      .setToLowercase(true)
    val words = tokenizer.transform(noPunctText)

    words.where(size(col(wordsColumn)) > 0)
  }
}