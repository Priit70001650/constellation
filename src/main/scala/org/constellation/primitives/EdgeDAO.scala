package org.constellation.primitives

import java.util.concurrent.Executors

import akka.actor.ActorRef
import org.constellation.primitives.Schema._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

trait EdgeDAO {

  var genesisObservation: Option[GenesisObservation] = None
  val maxWidth = 30
  val minCheckpointFormationThreshold = 100
  val minTXSignatureThreshold = 3
  val minCBSignatureThreshold = 3
  val maxUniqueTXSize = 500
  val maxNumSignaturesPerTX = 20

  @volatile var transactionMemPool: Seq[Transaction] = Seq()

  val transactionMemPoolMultiWitness : TrieMap[String, Transaction] = TrieMap()

  val checkpointMemPool : TrieMap[String, CheckpointBlock] = TrieMap()

  // TODO: temp
  val confirmedCheckpoints: TrieMap[String, CheckpointBlock] = TrieMap()

  @volatile var transactionMemPoolThresholdMet: Set[String] = Set()

  // Map from checkpoint hash to number of times used as a tip (number of children)
  val checkpointMemPoolThresholdMet: TrieMap[String, (CheckpointBlock, Int)] = TrieMap()

  val edgeExecutionContext: ExecutionContextExecutor =

    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(20))

  def canCreateCheckpoint: Boolean = {
    transactionMemPool.size >= minCheckpointFormationThreshold && checkpointMemPoolThresholdMet.size >= 2
  }

  def reuseTips: Boolean = checkpointMemPoolThresholdMet.size < maxWidth

  // Temporary to get peer data for tx hash partitioning
  @volatile var peerInfo: Map[Id, PeerData] = Map()


  /**
    * Storage for cb missing ancestors. Hash of soe to every checkpoint that depends upon it
    */
  val resolveNotifierCallbacks: TrieMap[String, Seq[CheckpointBlock]] = TrieMap()

  var snapshotRelativeTips: Set[CheckpointBlock] = Set()

  val branches: TrieMap[String, CheckpointBlock] = TrieMap()

  @volatile var stateBranchTip: String = ""

  var snapshot: Option[CheckpointBlock] = genesisObservation.map(_.genesis)//todo unbreak things
}
