package cromwell.backend.impl.jes

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import cromwell.backend.BackendSingletonActorAbortWorkflow
import cromwell.backend.impl.jes.statuspolling.JesApiQueryManager
import cromwell.backend.impl.jes.statuspolling.JesApiQueryManager.JesApiQueryManagerRequest
import cromwell.core.Dispatcher.BackendDispatcher
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

final case class JesBackendSingletonActor(qps: Int Refined Positive, serviceRegistryActor: ActorRef) extends Actor with ActorLogging {

  val jesApiQueryManager = context.actorOf(JesApiQueryManager.props(qps, serviceRegistryActor))

  override def receive = {
    case abort: BackendSingletonActorAbortWorkflow => jesApiQueryManager.forward(abort)
    case apiQuery: JesApiQueryManagerRequest =>
      log.debug("Forwarding API query to JES API query manager actor")
      jesApiQueryManager.forward(apiQuery)
  }
}

object JesBackendSingletonActor {
  def props(qps: Int Refined Positive, serviceRegistryActor: ActorRef): Props = Props(JesBackendSingletonActor(qps, serviceRegistryActor)).withDispatcher(BackendDispatcher)
}
