package org.constellation.primitives
import cats.effect.{ContextShift, IO}
import cats.implicits._
import org.constellation.DAO
import org.constellation.consensus.TipData
import org.constellation.util.Metrics
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.{FunSpecLike, Matchers}

class TipServiceTest extends FunSpecLike with IdiomaticMockito with ArgumentMatchersSugar with Matchers {

  implicit val dao: DAO = prepareDAO()
  implicit val ioContextShift: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

  def prepareDAO(): DAO = {
    val dao = mock[DAO]
    dao.metrics shouldReturn mock[Metrics]
    dao.metrics.incrementMetricAsync(*) shouldReturn IO.unit
    dao.metrics.updateMetricAsync(*, any[Int]) shouldReturn IO.unit
    dao
  }

  describe("TrieBasedTipService") {

    it("limits maximum number of tips") {
      val limit = 6
      val concurrentTipService = new TrieBasedTipService(limit, 10, 2, 30)

      val cbs = createIndexedCBmocks(limit * 3, { i =>
        createCBMock(i.toString)
      })

      val tasks = createShiftedTasks(cbs.toList, { cb =>
        concurrentTipService.update(cb)
      })
      tasks.par.foreach(_.unsafeRunAsyncAndForget)
      Thread.sleep(2000)
      concurrentTipService.toMap.size shouldBe limit
    }

  }

  private def prepareTransactions(): Seq[Transaction] = {
    val tx1 = mock[Transaction]
    tx1.hash shouldReturn "tx1"
    val tx2 = mock[Transaction]
    tx2.hash shouldReturn "tx2"
    Seq(tx1, tx2)
  }

  private def createCBMock(hash: String) = {
    val cb = mock[CheckpointBlock]
    cb.parentSOEBaseHashes shouldReturn Seq.empty
//    cb.transactions shouldReturn Seq.empty
    cb.baseHash shouldReturn hash
    cb
  }

  def createIndexedCBmocks(size: Int, func: Int => CheckpointBlock) = {
    (1 to size).map(func)
  }

  def createShiftedTasks(
    cbs: List[CheckpointBlock],
    func: CheckpointBlock => IO[Option[TipData]]
  ) =
    cbs.map(IO.shift *> func(_))

}
