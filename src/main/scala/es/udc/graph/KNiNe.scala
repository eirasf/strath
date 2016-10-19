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

object sparkContextSingleton
{
  @transient private var instance: SparkContext = _
  private val conf : SparkConf = new SparkConf().setAppName("TestStrath")
                                                .setMaster("local")
                                                .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                                                .set("spark.broadcast.factory", "org.apache.spark.broadcast.HttpBroadcastFactory")
                                                //.set("spark.eventLog.enabled", "true")
                                                //.set("spark.eventLog.dir","file:///home/eirasf/Escritorio/Tmp-work/sparklog-local")
                                                .set("spark.kryoserializer.buffer.max", "512")
                                                .set("spark.driver.maxResultSize", "2048")

  def getInstance(): SparkContext=
  {
    if (instance == null)
      instance = new SparkContext(conf)
    instance
  }  
}

object KNiNe
{
    def main(args: Array[String])
    {
      if (args.length <= 0)
      {
        println("An input libsvm file must be provided")
        return
      }
      
      var file=args(0)
      
      //Set up Spark Context
      val sc=sparkContextSingleton.getInstance()
      
      //Stop annoying INFO messages
      val rootLogger = Logger.getRootLogger()
      rootLogger.setLevel(Level.WARN)
      
      //Load data from file
      val data: RDD[(LabeledPoint, Long)] = MLUtils.loadLibSVMFile(sc, file).zipWithIndex()
      
      /*
      //Normalize if necessary
      val maxMins=data.map({case (point, index) => (point.features.toArray, point.features.toArray)})
                      .reduce({case ((max1, min1), (max2, min2)) =>
                                var maxLength=max1.length
                                var longMax=max1
                                var longMin=min1
                                var shortMax=max2
                                var shortMin=min2
                                if (max2.length>maxLength)
                                {
                                  maxLength=max2.length
                                  longMax=max2
                                  longMin=min2
                                  shortMax=max1
                                  shortMin=min1
                                }
                                for (i <- 0 until maxLength)
                                {
                                  if (i<shortMax.length)
                                  {
                                    if (longMax(i)<shortMax(i))
                                      longMax(i)=shortMax(i)
                                    if (longMin(i)>shortMin(i))
                                      longMin(i)=shortMin(i)
                                  }
                                }
                                (longMax, longMin)
                              })
      val ranges=new Array[Double](maxMins._1.length)
      for (i <- 0 until ranges.length)
        ranges(i)=maxMins._1(i)-maxMins._2(i)
      
      val normalizedData=data.map({case (point, index) =>
                                      val feats=point.features
                                      if (feats.isInstanceOf[DenseVector])
                                      {
                                        val dense=feats.asInstanceOf[DenseVector].values
                                        for (i <- 0 until dense.size)
                                          dense(i)=(dense(i)-maxMins._2(i))/ranges(i)
                                      }
                                      else
                                      {
                                        val sparse=feats.asInstanceOf[SparseVector]
                                        val indices=sparse.indices
                                        val values=sparse.values
                                        
                                        for (i <- 0 until indices.length)
                                          values(i)=(values(i)-maxMins._2(indices(i)))/ranges(indices(i))
                                      }
                                      (point, index)
                                  })
      
      println("Normalized dataset")
      normalizedData.foreach(println(_))
      */
      
      val n=data.count()
      val dimension=data.map(_._1.features.size).max() //TODO Dimension should be either read from the dataset or input by the user
      println("Dataset has "+n+" elements and dimension +"+dimension)
      
      val numNeighbors=2 //TODO Should be input from user
      
      /* GRAPH VERSION 
      
      val graph=LSHGraphXKNNGraphBuilder.getGraph(data, numNeighbors, dimension)
      println("There goes the graph:")
      graph.foreach(println(_))
      
      */
      
      
      /* LOOKUP VERSION */
      
      val graph=LSHLookupKNNGraphBuilder.computeGraph(data, numNeighbors, dimension)
      //Print graph
      println("There goes the graph:")
      graph.foreach({case (elementIndex, neighbors) =>
                      for(n <- neighbors)
                        println(elementIndex+"->"+n._1+"("+n._2+")")
                    })
      /**/
                    
      /* BRUTEFORCE VERSION
      
      val graph=LocalBruteForceKNNGraphBuilder.computeGraph(data, numNeighbors)
      //Print graph
      println("There goes the graph:")
      graph.foreach({case (elementIndex, neighbors) =>
                      for(n <- neighbors)
                        println(elementIndex+"->"+n._1+"("+n._2+")")
                    })
      */
      
      //Stop the Spark Context
      sc.stop()
    }
  }