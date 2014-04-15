package lila.ai

import actorApi._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }

private[ai] final class Puller(config: Config, id: Int) extends Actor {

  import Puller._

  private val name: String = s"fsm-$id"
  private val master = context.parent
  private val worker: ActorRef = context.actorOf(
    Props(new ActorFSM(name, Process(config.execPath, s"proc-$id"), config)),
    name = name)

  private def pull {
    context.system.scheduler.scheduleOnce(200 milliseconds, master, GimmeWork)
  }

  def idle: Receive = {

    case NoWork => pull

    case task: Task =>
      context become busy
      implicit val tm = task.timeout
      worker ? task.req onComplete { res =>
        self ! Complete(task, res)
      }

    case c: Complete => play.api.Logger(name).warn("Received complete while idle!")
  }

  def busy: Receive = {

    case Complete(task, Success(res)) =>
      context become idle
      task.replyTo ! res
      master ! GimmeWork

    case Complete(task, Failure(err)) =>
      task.replyTo ! Status.Failure(err)
      task.again map Enqueue.apply foreach master.!
      throw err

    case t: Task =>
      play.api.Logger(name).warn("Received task while busy! Sending back to master...")
      master ! Enqueue(t)

    case NoWork => play.api.Logger(name).warn("Received nowork while busy!")
  }

  def receive = idle

  override def preStart() {
    master ! GimmeWork
  }
}

private[ai] object Puller {

  case object GimmeWork
  case object NoWork
  case class Complete(task: Task, res: Try[Any])
  case class Enqueue(task: Task)

  case class Task(
      req: Req,
      replyTo: ActorRef,
      timeout: Timeout,
      date: Int = nowSeconds,
      isRetry: Boolean = false) extends Ordered[Task] {

    def again = !isRetry option copy(isRetry = true)

    def priority = req match {
      case _: PlayReq => 20
      case _: AnalReq => 10
    }

    def compare(other: Task): Int = priority compare other.priority match {
      case 0 => other.date compare date
      case x => x
    }
  }
}