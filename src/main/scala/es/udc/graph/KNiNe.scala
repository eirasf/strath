package es.udc.graph

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.SparkConf
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD.rddToPairRDDFunctions
import Array._
import scala.util.Random
import scala.util.control.Breaks._
import org.apache.spark.mllib.linalg.DenseVector
import org.apache.spark.mllib.linalg.SparseVector
import es.udc.graph.utils.GraphUtils
import org.apache.spark.mllib.linalg.Vectors
import org.apache.log4j.{Level, Logger}
import breeze.linalg.{DenseVector => BDV}

import sys.process._
import org.apache.spark.sql.SparkSession
import org.apache.spark.HashPartitioner

//import org.apache.spark.sql.SparkSession


object sparkContextSingleton
{
  /*@transient private var instance: SparkContext = _
  private val conf : SparkConf = new SparkConf().setAppName("KNiNe")
                                                .setMaster("local[8]")
                                                //.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                                                //.set("spark.broadcast.factory", "org.apache.spark.broadcast.HttpBroadcastFactory")
                                                //.set("spark.kryoserializer.buffer.max", "512")
                                                //.set("spark.driver.maxResultSize", "2048")
	*/
  def getInstance(): SparkContext=
  {
    val spark = SparkSession.builder//.appName("KNiNe")
                                    //.master("local[1]")
                                    //.config("spark.driver.maxResultSize", "2048MB")
                                    .getOrCreate()
    /*if (instance == null)
      instance = SparkContext.getOrCreate(conf)//new SparkContext(conf)
    instance*/
    spark.sparkContext
  }
}

object KNiNeConfiguration
{
  def getConfigurationFromOptions(options:Map[String, Any]):KNiNeConfiguration=
  {
    val radius0=if (options.exists(_._1=="radius_start"))
                      Some(options("radius_start").asInstanceOf[Double])
                    else
                      None
    val numTables=if (options.exists(_._1=="num_tables"))
                      Some(options("num_tables").asInstanceOf[Double].toInt)
                    else
                      None
    val keyLength=if (options.exists(_._1=="key_length"))
                      Some(options("key_length").asInstanceOf[Double].toInt)
                    else
                      None
    val maxComparisons=if (options.exists(_._1=="max_comparisons"))
                         Some(options("max_comparisons").asInstanceOf[Double].toInt)
                       else
                         None
    val blockSz=if (options.exists(_._1=="blocksz"))
                         options("blocksz").asInstanceOf[Int]
                       else
                         KNiNe.DEFAULT_BLOCKSZ
    val iterations=if (options.exists(_._1=="iterations"))
                         options("iterations").asInstanceOf[Int]
                       else
                         KNiNe.DEFAULT_ITERATIONS
    return new KNiNeConfiguration(numTables, keyLength, maxComparisons, radius0, options("refine").asInstanceOf[Int], blockSz, iterations)
  }
}

class KNiNeConfiguration(val numTables:Option[Int], val keyLength:Option[Int], val maxComparisons:Option[Int], val radius0:Option[Double], val refine:Int, val blockSz:Int, val iterations:Int)
{
  def this() = this(None, None, None, None, KNiNe.DEFAULT_REFINEMENT, KNiNe.DEFAULT_BLOCKSZ, KNiNe.DEFAULT_ITERATIONS)
  override def toString():String=
  {
    return "R0="+this.radius0+";NT="+this.numTables+";KL="+this.keyLength+";MC="+this.maxComparisons+";Refine="+this.refine
  }
}

object KNiNe
{
  val DEFAULT_METHOD="vrlsh"
  val DEFAULT_K=10
  val DEFAULT_REFINEMENT=1
  val DEFAULT_NUM_PARTITIONS:Double=512
  val DEFAULT_BLOCKSZ:Int=100
  val DEFAULT_ITERATIONS:Int=1

  def showUsageAndExit()=
  {
    println("""Usage: KNiNe dataset output_file [options]
    Dataset must be a libsvm or text file
Options:
    -k    Number of neighbors (default: """+KNiNe.DEFAULT_K+""")
    -m    Method used to compute the graph. Valid values: vrlsh, brute, fastKNN-proj, fastKNN-AGH (default: """+KNiNe.DEFAULT_METHOD+""")
    -r    Starting radius (default: """+LSHKNNGraphBuilder.DEFAULT_RADIUS_START+""")
    -t    Maximum comparisons per item (default: auto)
    -c    File containing the graph to compare to (default: nothing)
    -p    Number of partitions for the data RDDs (default: """+KNiNe.DEFAULT_NUM_PARTITIONS+""")
    -d    Number of refinement (descent) steps (LSH only) (default: """+KNiNe.DEFAULT_REFINEMENT+""")
    -b    blockSz (fastKNN only) (default: """+KNiNe.DEFAULT_BLOCKSZ+""")
    -i    iterations (fastKNN only) (default: """+KNiNe.DEFAULT_ITERATIONS+""")

Advanced LSH options:
    -n    Number of hashes per item (default: auto)
    -l    Hash length (default: auto)

""")
    System.exit(-1)
  }
  def parseParams(p:Array[String]):Map[String, Any]=
  {
    val m=scala.collection.mutable.Map[String, Any]("num_neighbors" -> KNiNe.DEFAULT_K.toDouble,
                                                    "method" -> KNiNe.DEFAULT_METHOD,
                                                    "radius_start" -> LSHKNNGraphBuilder.DEFAULT_RADIUS_START,
                                                    "num_partitions" -> KNiNe.DEFAULT_NUM_PARTITIONS,
                                                    "refine" -> KNiNe.DEFAULT_REFINEMENT)
    if (p.length<=1)
      showUsageAndExit()

    m("dataset")=p(0)
    m("output")=p(1)

    var i=2
    while (i < p.length)
    {
      if ((i>=p.length) || (p(i).charAt(0)!='-'))
      {
        println("Unknown option: "+p(i))
        showUsageAndExit()
      }
      val readOptionName=p(i).substring(1)
      val option=readOptionName match
        {
          case "k"   => "num_neighbors"
          case "m"   => "method"
          case "r"   => "radius_start"
          case "n"   => "num_tables"
          case "l"   => "key_length"
          case "t"   => "max_comparisons"
          case "c"   => "compare"
          case "p"   => "num_partitions"
          case "d"   => "refine"
          case "b"   => "blocksz"
          case "i"   => "iterations"
          case somethingElse => readOptionName
        }
      if (!m.keySet.exists(_==option) && option==readOptionName)
      {
        println("Unknown option:"+readOptionName)
        showUsageAndExit()
      }
      if (option=="method")
      {
        if (p(i+1)=="vrlsh" || p(i+1)=="brute" || p(i+1)=="fastKNN-proj" || p(i+1)=="fastKNN-AGH")
          m(option)=p(i+1)
        else
        {
          println("Unknown method:"+p(i+1))
          showUsageAndExit()
        }
      }
      else
      {
        if (option=="compare")
          m(option)=p(i+1)
        else
          if ((option=="refine") || (option=="blocksz") || (option=="iterations"))
            m(option)=p(i+1).toInt
          else
            m(option)=p(i+1).toDouble
      }

      i=i+2
    }
    return m.toMap
  }
  def main(args: Array[String])
  {
    if (args.length <= 0)
    {
      showUsageAndExit()
      return
    }

    val options=parseParams(args)

    val datasetFile=options("dataset").asInstanceOf[String]

    val fileParts=datasetFile.split("/")
    var justFileName=fileParts(fileParts.length-1).split("\\.")(0)
//val file="/home/eirasf/Escritorio/kNNTEMP/car-dopado.libsvm"
    val numNeighbors=options("num_neighbors").asInstanceOf[Double].toInt
    val numPartitions=options("num_partitions").asInstanceOf[Double].toInt
    val method=options("method")
    val format=if ((datasetFile.length()>7) && (datasetFile.substring(datasetFile.length()-7) ==".libsvm"))
                 "libsvm"
               else
                 "text"

    val compareFile=if (options.exists(_._1=="compare"))
                      options("compare").asInstanceOf[String]
                    else
                      null

    val kNiNeConf=KNiNeConfiguration.getConfigurationFromOptions(options)

    //println("Using "+method+" to compute "+numNeighbors+"NN graph for dataset "+justFileName)
    //println("R0:"+radius0+(if (numTables!=null)" num_tables:"+numTables else "")+(if (keyLength!=null)" keyLength:"+keyLength else "")+(if (maxComparisons!=null)" maxComparisons:"+maxComparisons else ""))

    //Set up Spark Context
    val sc=sparkContextSingleton.getInstance()
    println(s"Default parallelism: ${sc.defaultParallelism}")
    //Stop annoying INFO messages
    val rootLogger = Logger.getRootLogger()
    rootLogger.setLevel(Level.WARN)

    //Load data from file
    val data: RDD[(Long,LabeledPoint)] = (if (format=="libsvm")
                                            MLUtils.loadLibSVMFile(sc, datasetFile).zipWithIndex().map(_.swap)
                                          else
                                          {
                                            val rawData=sc.textFile(datasetFile,numPartitions)
                                            rawData.map({ line => val values=line.split(";")
                                                                  (values(0).toLong-1, new LabeledPoint(0.0, Vectors.dense(values.slice(1, values.length).map { x => x.toDouble })))
                                                        })
                                          }).partitionBy(new HashPartitioner(numPartitions))

    /* DATASET INSPECTION - DEBUG
    val summary=data.map({case x => (x._1.features.toArray,x._1.features.toArray,x._1.features.toArray)}).reduce({case ((as,aM,am),(bs,bM,bm)) => (as.zip(bs).map({case (ea,eb) => ea+eb}),aM.zip(bM).map({case (ea,eb) => Math.max(ea,eb)}),am.zip(bm).map({case (ea,eb) => Math.min(ea,eb)}))})
    val total=data.count()
    val medias=summary._1.map({ x => x/total })
    val spans=summary._2.zip(summary._3).map({case (a,b) => (a-b)})
    println(Vectors.dense(medias))
    println(Vectors.dense(spans))
    val stddevs=data.map(_._1.features.toArray.zip(medias).map({case (x,u) => (x-u)*(x-u) })).reduce({case (a,b) => a.zip(b).map({case (ea,eb) => ea+eb})}).map({ x => Math.sqrt(x/total) })
    println(Vectors.dense(stddevs))
    println(stddevs.max)
    println(stddevs.min)
    println(stddevs.sum/stddevs.length)
    System.exit(0)
    */

    //val n=data.count()
    //println("Dataset has "+n+" elements")

    /* GRAPH VERSION

    val graph=LSHGraphXKNNGraphBuilder.getGraph(data, numNeighbors, dimension)
    println("There goes the graph:")
    graph.foreach(println(_))

    */


    //EuclideanLSHasher.getBucketCount(data.map(_.swap), hasher, radius)
    //System.exit(0)


val timeStart=System.currentTimeMillis();
    var builder:GraphBuilder=null
    val (graph,lookup)=method match
            {
              case "fastKNN-AGH" =>
                  builder=new SimpleLSHLookupKNNGraphBuilder(data)
                          val kLength=data.map({case (id, p) =>
                                                  val h=p.label.toInt
                                                  var pow=0
                                                  while (math.pow(2, pow)<h)
                                                    pow+=1
                                                  pow
                                                }).max()
                  println(s"Method: fastKNN with AGH (must be precomputed on dataset labels) as LSH. BlockSz=${kNiNeConf.blockSz} Iterations=${kNiNeConf.iterations}  keyLength=$kLength")
                  builder=new SimpleLSHLookupKNNGraphBuilder(data)
                  (builder.asInstanceOf[SimpleLSHLookupKNNGraphBuilder].iterativeComputeGraph(data, numNeighbors, kLength, 0, new EuclideanDistanceProvider(), Some(kNiNeConf.blockSz), kNiNeConf.iterations, true),builder.asInstanceOf[SimpleLSHLookupKNNGraphBuilder].lookup)
              case "fastKNN-proj" =>
                println(s"Method: fastKNN with random projections as LSH. BlockSz=${kNiNeConf.blockSz} KeyLength=${kNiNeConf.keyLength.get}  NumTables=${kNiNeConf.numTables.get} Iterations=${kNiNeConf.iterations}")
                  builder=new SimpleLSHLookupKNNGraphBuilder(data)
                  (builder.asInstanceOf[SimpleLSHLookupKNNGraphBuilder].iterativeComputeGraph(data, numNeighbors, kNiNeConf.keyLength.get, kNiNeConf.numTables.get, new EuclideanDistanceProvider(), Some(kNiNeConf.blockSz), kNiNeConf.iterations),builder.asInstanceOf[SimpleLSHLookupKNNGraphBuilder].lookup)
              case "vrlsh" =>
                  /* LOOKUP VERSION */
                  builder=new LSHLookupKNNGraphBuilder(data)
                  if (kNiNeConf.keyLength.isDefined && kNiNeConf.numTables.isDefined)
                    (builder.asInstanceOf[LSHLookupKNNGraphBuilder].computeGraph(data, numNeighbors, kNiNeConf.keyLength.get, kNiNeConf.numTables.get, kNiNeConf.radius0, kNiNeConf.maxComparisons, new EuclideanDistanceProvider()),builder.asInstanceOf[LSHLookupKNNGraphBuilder].lookup)
                  else
                  {
                    //val cMax=if (kNiNeConf.maxComparisons>0) kNiNeConf.maxComparisons else 250
                    val cMax=if (kNiNeConf.maxComparisons.isDefined) math.max(kNiNeConf.maxComparisons.get,numNeighbors) else math.max(128,10*numNeighbors)
                    //val factor=if (options.contains("fast")) 4.0 else 0.8
                    val factor=2.0
                    val (hasher,nComps,suggestedRadius)=EuclideanLSHasher.getHasherForDataset(data, (cMax*factor).toInt) //Make constant size buckets
                    (builder.asInstanceOf[LSHLookupKNNGraphBuilder].computeGraph(data, numNeighbors, hasher, Some(suggestedRadius), Some(cMax.toInt), new EuclideanDistanceProvider()),builder.asInstanceOf[LSHLookupKNNGraphBuilder].lookup)
                  }
              case somethingElse =>
                  /* BRUTEFORCE VERSION */
                  BruteForceKNNGraphBuilder.parallelComputeGraph(data, numNeighbors, numPartitions)
            }
                   

    //Print graph
    /*println("There goes the graph:")
    graph.foreach({case (elementIndex, neighbors) =>
                    for(n <- neighbors)
                      println(elementIndex+"->"+n._1+"("+n._2+")")
                  })
    */

    //

    //DEBUG
    //var counted=edges.map({case x=>(x._1,1)}).reduceByKey(_+_).sortBy(_._1)
    //var forCount=counted.map(_._2)

    var countEdges=graph.map({case (index, neighbors) => neighbors.listNeighbors.toSet.size}).sum
    println("Obtained "+countEdges+" edges for "+graph.count()+" nodes in "+(System.currentTimeMillis()-timeStart)+" milliseconds")



    //Save to file
    var fileName=options("output").asInstanceOf[String]
    var fileNameOriginal=fileName
    // DEBUG - Skip save
    val skipSave=false
    if (!skipSave)
    {
      var i=0
      while (java.nio.file.Files.exists(java.nio.file.Paths.get(fileName.substring(7))))
      {
        i=i+1
        fileName=fileNameOriginal+"-"+i
      }
      val edges=graph.flatMap({case (index, neighbors) => neighbors.listNeighbors.map({case destPair => (index, destPair.index, math.sqrt(destPair.distance))}).toSet})
      edges.saveAsTextFile(fileName)
    }
    var fileNameR=fileName
    if (method!="brute")
    {
      var refinedGraph=graph.map({case (v, neighs) => (v, neighs.wrapWithCount(1))})
      for (i <- 0 until kNiNeConf.refine)
      {
        println(s"Performing neighbor descent step ${i+1}")
        val timeStartR=System.currentTimeMillis();
        refinedGraph=builder.refineGraph(data, refinedGraph, numNeighbors, new EuclideanDistanceProvider())
        fileNameR=fileName+"refined"+i
        val edgesR=refinedGraph.flatMap({case (index, neighs) =>
                                                   neighs.listNeighbors.map({case destPair =>
                                                                                             (index, destPair.index, math.sqrt(destPair.distance))}).toSet})
        //TODO - Move sqrt in previous line to graph class.
        val totalElements=data.count()
        val e=edgesR.first()
        println("Added "+(System.currentTimeMillis()-timeStartR)+" milliseconds")

        if (!skipSave)
          edgesR.saveAsTextFile(fileNameR)
      }
    }
    if (compareFile!=null)
    {
      //Compare with ground truth
      CompareGraphs.printResults(CompareGraphs.compare(compareFile, fileName, None))
      //CompareGraphs.comparePositions(compareFile.replace(numNeighbors+"", "128"), fileName)

      //Compare refined with ground truth
      if (fileName!=fileNameR)
        CompareGraphs.printResults(CompareGraphs.compare(compareFile, fileNameR, None))
      //CompareGraphs.comparePositions(compareFile.replace(numNeighbors+"", "128"), fileName)

      /* //DEBUG - Show how the graph has improved
      firstComparison.join(secondComparison)
                     .flatMap({case (element,((a,b,furthest,list), (a2,b2,furthest2,list2))) => if (b!=b2 || list!=list2)
                                                                                                  Some(element, b.diff(b2), b2.diff(b))
                                                                                                else
                                                                                                  None})
                     .sortBy(_._1)
                     .foreach(println(_))
      */
    }
    //Stop the Spark Context
    sc.stop()
  }
}
