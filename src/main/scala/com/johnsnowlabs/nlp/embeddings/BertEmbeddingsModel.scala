package com.johnsnowlabs.nlp.embeddings

import java.io.File

import com.johnsnowlabs.ml.tensorflow.{ReadTensorflowModel, TensorflowBert, TensorflowWrapper, WriteTensorflowModel}
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.common._
import com.johnsnowlabs.nlp.annotators.tokenizer.wordpiece.{BasicTokenizer, WordpieceEncoder}
import com.johnsnowlabs.nlp.pretrained.ResourceDownloader
import com.johnsnowlabs.nlp.serialization.MapFeature
import com.johnsnowlabs.nlp.util.io.{ExternalResource, ReadAs, ResourceHelper}
import org.apache.spark.ml.param.{BooleanParam, IntParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.SparkSession


class BertEmbeddingsModel(override val uid: String) extends
  AnnotatorModel[BertEmbeddingsModel]
  with WriteTensorflowModel {

  def this() = this(Identifiable.randomUID("BERT_EMBEDDINGS"))

  val maxSentenceLength = new IntParam(this, "maxSentenceLength", "Max sentence length to process")
  val batchSize = new IntParam(this, "batchSize", "Batch size. Large values allows faster processing but requires more memory.")
  val dim = new IntParam(this, "dim", "Dimension of embeddings")

  val vocabulary: MapFeature[String, Int] = new MapFeature(this, "vocabulary")
  val lowercase = new BooleanParam(this, name = "lowercase", "Should be lowercased")

  def setVocabulary(value: Map[String, Int]): this.type = set(vocabulary, value)

  def setLowercase(value: Boolean): this.type = set(lowercase, value)

  def sentenceStartTokenId: Int = {
    require(vocabulary.isSet)
    vocabulary.getOrDefault("[CLS]")
  }

  def sentenceEndTokenId: Int = {
    require(vocabulary.isSet)
    vocabulary.getOrDefault("[SEP]")
  }

  setDefault(
    dim -> 768,
    batchSize -> 5,
    maxSentenceLength -> 256,
    lowercase -> true
  )

  var tensorflow: TensorflowWrapper = null

  def setTensorflow(tf: TensorflowWrapper): this.type = {
    this.tensorflow = tf
    this
  }

  def setBatchSize(size: Int): this.type = set(batchSize, size)

  def setDim(value: Int): this.type = set(dim, value)

  def setMaxSentenceLength(value: Int): this.type = set(maxSentenceLength, value)

  @transient
  private var _model: TensorflowBert = null

  def getModel: TensorflowBert = {
    if (_model == null) {
      require(tensorflow != null, "Tensorflow must be set before usage. Use method setTensorflow() for it.")

      _model = new TensorflowBert(
        tensorflow,
        sentenceStartTokenId,
        sentenceEndTokenId,
        $(maxSentenceLength))
    }

    _model
  }

  def tokenize(sentences: Seq[Sentence]): Seq[WordpieceTokenizedSentence] = {
    val basicTokenizer = new BasicTokenizer($(lowercase))
    val encoder = new WordpieceEncoder(vocabulary.getOrDefault)

    sentences.map { s =>
      val tokens = basicTokenizer.tokenize(s)
      val wordpieceTokens = tokens.flatMap(token => encoder.encode(token))
      WordpieceTokenizedSentence(wordpieceTokens)
    }
  }

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val sentences = SentenceSplit.unpack(annotations)
    val tokenized = tokenize(sentences)
    val withEmbeddings = getModel.calculateEmbeddings(tokenized)
    WordpieceEmbeddingsSentence.pack(withEmbeddings)
  }

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val requiredAnnotatorTypes = Array(AnnotatorType.DOCUMENT)
  override val annotatorType: AnnotatorType = AnnotatorType.WORD_EMBEDDINGS

  override def onWrite(path: String, spark: SparkSession): Unit = {
    super.onWrite(path, spark)
    writeTensorflowModel(path, spark, tensorflow, "_bert", BertEmbeddingsModel.tfFile)
  }
}

trait PretrainedBertModel {
  def pretrained(name: String = "bert_uncased_base", language: Option[String] = None, remoteLoc: String = ResourceDownloader.publicLoc): BertEmbeddingsModel =
    ResourceDownloader.downloadModel(BertEmbeddingsModel, name, language, remoteLoc)
}

object BertEmbeddingsModel extends ParamsAndFeaturesReadable[BertEmbeddingsModel]
  with PretrainedBertModel
  with ReadTensorflowModel {

  override val tfFile: String = "bert_tensorflow"

  def readTensorflow(instance: BertEmbeddingsModel, path: String, spark: SparkSession): Unit = {
    val tf = readTensorflowModel(path, spark, "_bert_tf")
    instance.setTensorflow(tf)
  }

  addReader(readTensorflow)


  def loadFromPython(folder: String): BertEmbeddingsModel = {
    val f = new File(folder)
    val vocab = new File(folder, "vocab.txt")
    require(f.exists, s"Folder ${folder} not found")
    require(f.isDirectory, s"File ${folder} is not folder")
    require(vocab.exists(), s"Vocabulary file vocab.txt not found in folder ${folder}")

    val wrapper = TensorflowWrapper.read(folder, zipped = false)

    val vocabResource = new ExternalResource(vocab.getAbsolutePath, ReadAs.LINE_BY_LINE, Map("format" -> "text"))
    val words = ResourceHelper.parseLines(vocabResource).zipWithIndex.toMap

    new BertEmbeddingsModel()
      .setTensorflow(wrapper)
      .setVocabulary(words)
  }
}