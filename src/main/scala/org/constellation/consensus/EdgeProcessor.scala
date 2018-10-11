package org.constellation.consensus

import java.security.KeyPair
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import constellation._
import org.constellation.Data
import org.constellation.consensus.Consensus._
import org.constellation.consensus.EdgeProcessor.{HandleTransaction, _}
import org.constellation.consensus.Validation.TransactionValidationStatus
import org.constellation.primitives.Schema._
import org.constellation.primitives._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

object EdgeProcessor {

  case class HandleTransaction(tx: Transaction)
  case class HandleCheckpoint(checkpointBlock: CheckpointBlock)

  val logger = Logger(s"EdgeProcessor")
  implicit val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)


  def tipsMergeable(s1: SignedObservationEdge,
                    s2: SignedObservationEdge,
                    dao: Data): Boolean = {

    val genesisBaseHash = dao.genesisObservation.get.genesis.baseHash

    def gatherTransactionsAndCheckpointBlocks(
                                               s1: SignedObservationEdge,
                                               dao: Data
                                             ): Set[CheckpointBlock] = {
      /*
          Gather all unique checkpoint blocks, check for hash duplicate of transactions.
          If no duplicates, apply all transactions to a ledger, check for negative balance.
        */

      dao.dbActor.getCheckpointCacheData(s1.baseHash).map { cp1 =>
        if (cp1.checkpointBlock.baseHash != genesisBaseHash) {
          val Seq(a1, a2) = cp1.checkpointBlock.parentSOE

          val ar1 = gatherTransactionsAndCheckpointBlocks(a1, dao)
          val ar2 = gatherTransactionsAndCheckpointBlocks(a2, dao)

          ar1 ++ ar2 + cp1.checkpointBlock
        } else { Set(cp1.checkpointBlock) }
      }.getOrElse(throw new RuntimeException("CheckpointCacheData missing"))
    }


    val cbs1 = gatherTransactionsAndCheckpointBlocks(s1, dao)
    val cbs2 = gatherTransactionsAndCheckpointBlocks(s2, dao)

    val txs = (cbs1 ++ cbs2).toSeq.flatMap { x => x.transactions }
    val txsHash = txs.map(_.hash)
    // Get transactions from unique CP

    if (txsHash.distinct.size != txsHash.size) {
      logger.info(s"Duplicate transactions found: Distinct size: ${txsHash.distinct.size} - non distinct size: ${txsHash.size}")
      false
    } else {
      // Compute the ledger balance to look for double spends
      val tw = txs.flatMap { tx =>
        Seq((tx.src, -tx.amount), (tx.dst, tx.amount))
      }.groupBy(_._1).mapValues { _.map(_._2).sum }

      tw.foreach { case (k,v) =>
        if (v < 0) {
          logger.warn(s"Negative balance: $k: $v")
        }
      }

      // TODO: Exclude genesis TX
      tw.forall { case (addr, balance) => balance >= 0 }
    }


  }


  def handleCheckpoint(cb: CheckpointBlock,
                       dao: Data,
                       internalMessage: Boolean = false)(implicit executionContext: ExecutionContext): Unit = {

    if (!internalMessage) {
      dao.metricsManager ! IncrementMetric("checkpointMessages")
    } else {
      dao.metricsManager ! IncrementMetric("internalCheckpointMessages")
    }


    // TODO : Handle resolution issues.
    val potentialChildren = dao.resolveNotifierCallbacks.get(cb.soeHash)

    val cache = dao.dbActor.getCheckpointCacheData(cb.baseHash)

    def signFlow(): Unit = {
      // Mock
      // Check to see if we should add our signature to the CB

      // Note it's already met the threshold and we just haven't heard about it yet don't add signature
      // Not issue yet due to constraints of test.

      if (!dao.checkpointMemPoolThresholdMet.contains(cb.baseHash)) { // sanity check but shouldn't be required

        val cbPrime = updateCheckpointWithSelfSignatureEmit(cb, dao)

        // Add to memPool or update an existing hash with new signatures and check for signature threshold
        updateCheckpointMergeMemPool(cbPrime, dao)

        attemptFormCheckpointUpdateState(dao)
      }
    }

    def resolutionFlow(): Unit = {

      val resolved = Resolve.resolveCheckpoint(dao, cb)

      // TODO: Validate the checkpoint to see if any there are any duplicate transactions
      // Also need to register it with ldb ? To prevent duplicate processing ? Or after
      // If there are duplicate transactions, do a score calculation relative to the other one to determine which to preserve.
      // Potentially rolling back the other one.

      resolved.foreach { r =>

        if (r) {
          dao.metricsManager ! IncrementMetric("resolvedCheckpointMessages")

          val validatedTransactions = cb.transactions.map(Validation.validateTransaction(dao.dbActor, _))
          val validAccordingToCurrentState = validatedTransactions.forall(_.validByCurrentState)

            if (validAccordingToCurrentState) {
              signFlow()
            }
            else {
                val v = validatedTransactions.forall { s =>
                  cb.checkpoint.edge.parentHashes.forall { ancestorHash =>
                    s.validByAncestor(ancestorHash)
                  }
                }

                if (v) {
                  // resolveAncestryConflict(cb)
                }
              }
              // Check if parent tips are valid to merge.
              val s1 = cb.parentSOE.head
              val s2 = cb.parentSOE.last
              val mergeable = tipsMergeable(s1, s2, dao)


          // TODO: Process children
          // Also need to store child references in parent.
          potentialChildren.foreach{
            _.foreach{ c =>
              Future(handleCheckpoint(c, dao, true))
            }
          }
          // Post resolution. onComplete

          // Need something to check if valid by ancestors

        } else {
          dao.metricsManager ! IncrementMetric("unresolvedCheckpointMessages")

        }
      }

    }

    def mainFlow(): Unit = {
      // Base hash not stored in DB, no possible signature conflict
      if (cache.isEmpty) {
        dao.metricsManager ! IncrementMetric("unknownCheckpointMessages")
        // resolutionFlow()
        // DEBUG
        signFlow()
      } else {

        val ca = cache.get

        if (ca.resolved) {
          if (ca.inDAG) {
            if (ca.checkpointBlock != cb) {
              // Data mismatch on base hash lookup, i.e. signature conflict
              // TODO: Conflict resolution
              //resolveConflict(cb, ca)
            } else {
              // Duplicate checkpoint message, no action required.
            }
          } else {
            // warn or store information about potential conflict
            // if block is not yet in DAG then it doesn't matter, can just store updated value or whatever
          }
        } else {

          // Data is stored but not resolved, potentially trigger resolution check to see if something failed?
          // Otherwise do nothing as the resolution is already in progress.

        }

        // if (ca.checkpointBlock

      }

    }

    mainFlow()

    //  Resolve.resolveCheckpoint(dao, cb)
  }

  // TODO : Add checks on max number in mempool and max num signatures.

  // TEMPORARY mock-up for pre-consensus integration mimics transactions
  def updateCheckpointWithSelfSignatureEmit(cb: CheckpointBlock, dao: Data): CheckpointBlock = {
    val cbPrime = if (!cb.signatures.exists(_.publicKey == dao.keyPair.getPublic)) {
      // We haven't yet signed this CB
      val cb2 = cb.plus(dao.keyPair)
      dao.metricsManager ! IncrementMetric("signaturesPerformed")
      // Send peers new signature
      dao.peerManager ! APIBroadcast(_.put(s"checkpoint/${cb.baseHash}", cb2))
      dao.metricsManager ! IncrementMetric("checkpointBroadcasts")
      cb2
    } else {
      // We have already signed this CB,
      cb
    }
    cbPrime
  }

  // TEMPORARY mock-up for pre-consensus integration mimics transactions
  def updateCheckpointMergeMemPool(cb: CheckpointBlock, dao: Data) : Unit = {
    val cbPostUpdate = if (dao.checkpointMemPool.contains(cb.baseHash)) {
      // Merge signatures together
      val updated = dao.checkpointMemPool(cb.baseHash).plus(cb)
      // Update memPool with new signatures.
      dao.checkpointMemPool(cb.baseHash) = updated
      updated
    }
    else {
      dao.checkpointMemPool(cb.baseHash) = cb
      cb
    }

    // TODO: Verify this is still valid before accepting. And/or
    // consider removing from memPool if there's a conflict on something else being accepted.
    // Check to see if we have enough signatures to include in CB
    if (cbPostUpdate.signatures.size >= dao.minCBSignatureThreshold) {
      // Set threshold as met
      dao.checkpointMemPoolThresholdMet(cb.baseHash) = cb -> 0
      dao.checkpointMemPool.remove(cb.baseHash)

      dao.metricsManager ! UpdateMetric("checkpointMemPool", dao.checkpointMemPool.size.toString)

      cb.parentSOEBaseHashes.foreach {
        h =>
          dao.checkpointMemPoolThresholdMet.get(h).foreach {
            case (block, numUses) =>

              // TODO: move to tips service
              // Update tips
              def doRemove(): Unit = {
                dao.checkpointMemPoolThresholdMet.remove(h)
                dao.metricsManager ! IncrementMetric("checkpointTipsRemoved")
              }

              if (dao.reuseTips) {
                if (numUses >= 2) {
                  doRemove()
                } else {
                  dao.checkpointMemPoolThresholdMet(h) = (block, numUses + 1)
                }
              } else {
                doRemove()
              }
          }


      }
      dao.metricsManager ! UpdateMetric("checkpointMemPoolThresholdMet", dao.checkpointMemPoolThresholdMet.size.toString)

      // Accept transactions
      cb.transactions.foreach { t =>
        dao.metricsManager ! IncrementMetric("transactionAccepted")
        t.store(
          dao.dbActor,
          TransactionCacheData(
            t,
            valid = true,
            inMemPool = false,
            inDAG = true,
            Map(cb.baseHash -> true),
            resolved = true,
            cbBaseHash = Some(cb.baseHash)
          ))
        t.ledgerApply(dao.dbActor)
      }
      dao.metricsManager ! IncrementMetric("checkpointAccepted")
      cb.store(
        dao.dbActor,
        CheckpointCacheData(
          cb,
          inDAG = true,
          resolved = true
        ),
        resolved = true
      )
    }
  }


  def attemptFormCheckpointUpdateState(dao: Data): Option[CheckpointBlock] = {

    // TODO: Send a DBUpdate to modify tip data to include newly formed CB as a 'child', but only after acceptance
    if (dao.canCreateCheckpoint) {
      // Form new checkpoint block.

      // TODO : Validate this batch doesn't have a double spend, if it does,
      // just drop all conflicting. Shouldn't be necessary since memPool is already validated
      // relative to current state but it can't hurt

      val tips = Random.shuffle(dao.checkpointMemPoolThresholdMet.toSeq).take(2)

      val tipSOE = tips.map {_._2._1.checkpoint.edge.signedObservationEdge}

      /** Start of mergeable block **/
      val mergeable = tipsMergeable(tipSOE.head, tipSOE.last, dao)
      if (!mergeable) {
        logger.warn("tips not mergeable")
        // should actually end function here -- don't merge if not mergable.
      }
      /** End of mergeable block **/

      val transactions = Random.shuffle(dao.transactionMemPool).take(dao.minCheckpointFormationThreshold)
      dao.transactionMemPool = dao.transactionMemPool.filterNot(transactions.contains)

      val checkpointBlock = createCheckpointBlock(transactions, tipSOE)(dao.keyPair)
      dao.metricsManager ! IncrementMetric("checkpointBlocksCreated")

      val cbBaseHash = checkpointBlock.baseHash

      dao.checkpointMemPool(cbBaseHash) = checkpointBlock
      dao.metricsManager ! UpdateMetric("checkpointMemPool", dao.checkpointMemPool.size.toString)

      // Temporary bypass to consensus for mock
      // Send all data (even if this is redundant.)
      dao.peerManager ! APIBroadcast(_.put(s"checkpoint/$cbBaseHash", checkpointBlock))

      Some(checkpointBlock)
    } else None
  }

  /**
    * Main transaction processing cell
    * This is triggered upon external receipt of a transaction. Assume that the transaction being processed
    * came from a peer, not an internal operation.
    * @param tx : Transaction with all data
    * @param dao : Data access object for referencing memPool and other actors
    * @param executionContext : Threadpool to execute transaction processing against. Should be separate
    *                         from other pools for processing different operations.
    */
  def handleTransaction(
                         tx: Transaction, dao: Data
                       )(implicit executionContext: ExecutionContext): Unit = {

    // TODO: Store TX in DB and during signing updates delete the old SOE ? Or clean it up later?
    // SOE will appear multiple times as signatures are added together.

    dao.metricsManager ! IncrementMetric("transactionMessagesReceived")
    // Validate transaction TODO : This can be more efficient, calls get repeated several times
    // in event where a new signature is being made by another peer it's most likely still valid, should
    // cache the results of this somewhere.

    val finished = Validation.validateTransaction(dao.dbActor, tx)

    finished match {
      // TODO : Increment metrics here for each case
      case t : TransactionValidationStatus if t.validByCurrentStateMemPool =>

        if (!dao.transactionMemPool.contains(tx)) {


          // TODO:  Use XOR for random partition assignment later.
          /*
                    val idFraction = (dao.peerInfo.keys.toSeq :+ dao.id).map{ id =>
                      val bi = BigInt(id.id.getEncoded)
                      val bi2 = BigInt(tx.hash, 16)
                      val xor = bi ^ bi2
                      id -> xor
                    }.maxBy(_._2)._1
          */

          // We should process this transaction hash
          //  if (idFraction == dao.id) {

          dao.transactionMemPool :+= tx
          attemptFormCheckpointUpdateState(dao)

          dao.metricsManager ! IncrementMetric("transactionValidMessages")
          dao.metricsManager ! UpdateMetric("transactionMemPool", dao.transactionMemPool.size.toString)
          //dao.metricsManager ! UpdateMetric("transactionMemPoolThresholdMet", dao.transactionMemPoolThresholdMet.size.toString)
          //   }

        } else {
          dao.metricsManager ! IncrementMetric("transactionValidMemPoolDuplicateMessages")

        }

      //        triggerCheckpointBlocking(dao, txPrime)
      // var txStatusUpdatedInDB : Boolean = false
      /* val checkpointBlock = attemptFormCheckpointUpdateState(dao)

       if (checkpointBlock.exists{_.transactions.contains(tx)}) {
         txStatusUpdatedInDB = true
       }
      */
      /*
              if (!txStatusUpdatedInDB && t.transactionCacheData.isEmpty) {
                // TODO : Store something here for status queries. Make sure it doesn't cause a conflict
                //tx.edge.storeData(dao.dbActor) // This call can always overwrite no big deal
                // dao.dbActor ! DBUpdate//(tx.baseHash)
              }
      */

      case t : TransactionValidationStatus =>

        // TODO : Add info somewhere so node can find out transaction was invalid on a callback
        reportInvalidTransaction(dao: Data, t: TransactionValidationStatus)
    }

  }

  def reportInvalidTransaction(dao: Data, t: TransactionValidationStatus): Unit = {
    dao.metricsManager ! IncrementMetric("invalidTransactions")
    if (t.isDuplicateHash) {
      dao.metricsManager ! IncrementMetric("hashDuplicateTransactions")
    }
    if (!t.sufficientBalance) {
      dao.metricsManager ! IncrementMetric("insufficientBalanceTransactions")
    }
  }

  def triggerCheckpointBlocking(dao: Data,
                                tx: Transaction)(implicit timeout: Timeout, executionContext: ExecutionContext): Unit = {
    if (dao.canCreateCheckpoint) {

      println(s"starting checkpoint blocking")

      val checkpointBlock = attemptFormCheckpointUpdateState(dao).get

      dao.metricsManager ! IncrementMetric("checkpointBlocksCreated")

      val cbBaseHash = checkpointBlock.baseHash
      dao.checkpointMemPool(cbBaseHash) = checkpointBlock

      // TODO: should be subset
      val facilitators = (dao.peerManager ? GetPeerInfo).mapTo[Map[Id, PeerData]].get().keySet

      // TODO: what is the round hash based on?
      // what are thresholds and checkpoint selection

      val obe = checkpointBlock.checkpoint.edge.observationEdge

      val roundHash = RoundHash(obe.left.hash + obe.right.hash)

      // Start check pointing consensus round
/*      dao.consensus ! InitializeConsensusRound(facilitators, roundHash, (result) => {
        println(s"consensus round complete result roundHash = $roundHash, result = $result")
        EdgeProcessor.handleCheckpoint(result.checkpointBlock, dao)
      }, CheckpointVote(checkpointBlock))*/
      dao.consensus ! ConsensusVote(dao.id, CheckpointVote(checkpointBlock), roundHash)

    }
  }

  case class CreateCheckpointEdgeResponse(
                                           checkpointEdge: CheckpointEdge,
                                           transactionsUsed: Set[String],
                                           // filteredValidationTips: Seq[SignedObservationEdge],
                                           updatedTransactionMemPoolThresholdMet: Set[String]
                                         )


  def createCheckpointBlock(transactions: Seq[Transaction], tips: Seq[SignedObservationEdge])
                           (implicit keyPair: KeyPair): CheckpointBlock = {

    val checkpointEdgeData = CheckpointEdgeData(transactions.map{_.hash}.sorted)

    val observationEdge = ObservationEdge(
      TypedEdgeHash(tips.head.hash, EdgeHashType.CheckpointHash),
      TypedEdgeHash(tips(1).hash, EdgeHashType.CheckpointHash),
      data = Some(TypedEdgeHash(checkpointEdgeData.hash, EdgeHashType.CheckpointDataHash))
    )

    val soe = signedObservationEdge(observationEdge)(keyPair)

    val checkpointEdge = CheckpointEdge(
      Edge(observationEdge, soe, ResolvedObservationEdge(tips.head, tips(1), Some(checkpointEdgeData)))
    )

    CheckpointBlock(transactions, checkpointEdge)
  }

  def createCheckpointEdgeProposal(
                                    transactionMemPoolThresholdMet: Set[String],
                                    minCheckpointFormationThreshold: Int,
                                    tips: Seq[SignedObservationEdge],
                                  )(implicit keyPair: KeyPair): CreateCheckpointEdgeResponse = {

    val transactionsUsed = transactionMemPoolThresholdMet.take(minCheckpointFormationThreshold)
    val updatedTransactionMemPoolThresholdMet = transactionMemPoolThresholdMet -- transactionsUsed

    val checkpointEdgeData = CheckpointEdgeData(transactionsUsed.toSeq.sorted)

    //val tips = validationTips.take(2)
    //val filteredValidationTips = validationTips.filterNot(tips.contains)

    val observationEdge = ObservationEdge(
      TypedEdgeHash(tips.head.hash, EdgeHashType.CheckpointHash),
      TypedEdgeHash(tips(1).hash, EdgeHashType.CheckpointHash),
      data = Some(TypedEdgeHash(checkpointEdgeData.hash, EdgeHashType.CheckpointDataHash))
    )

    val soe = signedObservationEdge(observationEdge)(keyPair)

    val checkpointEdge = CheckpointEdge(Edge(observationEdge, soe, ResolvedObservationEdge(tips.head, tips(1), Some(checkpointEdgeData))))

    CreateCheckpointEdgeResponse(checkpointEdge, transactionsUsed,
      //filteredValidationTips,
      updatedTransactionMemPoolThresholdMet)
  }

  // TODO: Re-enable this section later, turning off for now for simplicity
  // Required later for dependency blocks / app support
  /**
    * Potentially add our signature to a transaction and if we haven't yet signed it emit to peers
    * @param tx : Transaction
    * @param dao : Data access object
    * @return Maybe updated transaction
    */
  def updateWithSelfSignatureEmit(tx: Transaction, dao: Data): Transaction = {
    val sigExists = tx.signatures.exists(_.publicKey == dao.keyPair.getPublic)
    val txPrime = if (!sigExists) {
      // We haven't yet signed this TX
      val tx2 = tx.plus(dao.keyPair)
      dao.metricsManager ! IncrementMetric("signaturesPerformed")
      // Send peers new signature
      dao.peerManager ! APIBroadcast(_.put(s"transaction/${tx.edge.signedObservationEdge.signatureBatch.hash}", tx2))
      dao.metricsManager ! IncrementMetric("transactionBroadcasts")
      tx2
    } else {
      // We have already signed this transaction,
      tx
    }
    txPrime
  }

  // TODO: Re-enable this section later, turning off for now for simplicity
  // Required later for dependency blocks / app support
  /**
    * Check the memPool to see if signatures are already stored under same OE hash,
    * if so, add current signatures to existing ones. Otherwise, store this in memPool
    * @param tx : Transaction after self signature added
    * @param dao : Data access object
    * @return : Potentially updated transaction.
    */
  def updateMergeMemPool(tx: Transaction, dao: Data) : Unit = {
    val txPostUpdate = if (dao.transactionMemPoolMultiWitness.contains(tx.baseHash)) {
      // Merge signatures together
      val updated = dao.transactionMemPoolMultiWitness(tx.baseHash).plus(tx)
      // Update memPool with new signatures.
      dao.transactionMemPoolMultiWitness(tx.baseHash) = updated
      updated
    }
    else {
      tx.ledgerApplyMemPool(dao.dbActor)
      dao.transactionMemPoolMultiWitness(tx.baseHash) = tx
      tx
    }

    // Check to see if we have enough signatures to include in CB
    if (txPostUpdate.signatures.size >= dao.minTXSignatureThreshold) {
      // Set threshold as met
      dao.transactionMemPoolThresholdMet += tx.baseHash
    }
  }

}

class EdgeProcessor(dao: Data)
                   (implicit timeout: Timeout, executionContext: ExecutionContext) extends Actor with ActorLogging {

  implicit val sys: ActorSystem = context.system
  implicit val kp: KeyPair = dao.keyPair

  def receive: Receive = {

    case HandleTransaction(transaction) =>
      log.debug(s"handle transaction = $transaction")

      handleTransaction(transaction, dao)

    case ConsensusRoundResult(checkpointBlock, roundHash: RoundHash[Checkpoint]) =>
      log.debug(s"handle checkpointBlock = $checkpointBlock")

      handleCheckpoint(checkpointBlock, dao)
  }

}
