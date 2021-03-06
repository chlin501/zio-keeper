package zio.keeper.swim.protocols

import upickle.default.macroRW
import zio.{ ZIO, keeper }
import zio.duration.Duration
import zio.keeper.swim.Nodes._
import zio.keeper.swim.{ ConversationId, Message, Nodes, Protocol }
import zio.keeper.{ ByteCodec, NodeAddress }

sealed trait Suspicion

object Suspicion {

  final case class Suspect(from: NodeAddress, nodeId: NodeAddress) extends Suspicion
  final case class Alive(nodeId: NodeAddress)                      extends Suspicion
  final case class Dead(nodeId: NodeAddress)                       extends Suspicion

  implicit val suspectCodec: ByteCodec[Suspect] =
    ByteCodec.fromReadWriter(macroRW[Suspect])

  implicit val aliveCodec: ByteCodec[Alive] =
    ByteCodec.fromReadWriter(macroRW[Alive])

  implicit val deadCodec: ByteCodec[Dead] =
    ByteCodec.fromReadWriter(macroRW[Dead])

  implicit val byteCodec: ByteCodec[Suspicion] =
    ByteCodec.tagged[Suspicion][
      Suspect,
      Alive,
      Dead
    ]

  type Env = ConversationId with Nodes

  def protocol(local: NodeAddress, timeout: Duration): ZIO[Env, keeper.Error, Protocol[Suspicion]] =
    Protocol[Suspicion].make(
      {
        case Message.Direct(sender, _, Suspect(_, `local`)) =>
          Message
            .direct(sender, Alive(local))
            .map(
              Message.Batch(
                _,
                Message.Broadcast(Alive(local))
              )
            )

        case Message.Direct(_, _, Suspect(_, node)) =>
          nodeState(node)
            .orElseSucceed(NodeState.Dead)
            .flatMap {
              case NodeState.Dead | NodeState.Suspicion =>
                Message.noResponse
              case _ =>
                changeNodeState(node, NodeState.Suspicion).ignore *>
                  Message.noResponse //it will trigger broadcast by events
            }

        case Message.Direct(sender, _, msg @ Dead(nodeAddress)) if sender == nodeAddress =>
          changeNodeState(nodeAddress, NodeState.Left).ignore
            .as(Message.Broadcast(msg))

        case Message.Direct(_, _, msg @ Dead(nodeAddress)) =>
          nodeState(nodeAddress).orElseSucceed(NodeState.Dead).flatMap {
            case NodeState.Dead => Message.noResponse
            case _ =>
              changeNodeState(nodeAddress, NodeState.Dead).ignore
                .as(Message.Broadcast(msg))
          }

        case Message.Direct(_, _, msg @ Alive(nodeAddress)) =>
          changeNodeState(nodeAddress, NodeState.Healthy).ignore
            .as(Message.Broadcast(msg))
      },
      internalEvents.collectM {
        case NodeStateChanged(node, _, NodeState.Suspicion) =>
          Message.withTimeout(
            Message.Broadcast(Suspect(local, node)),
            ZIO.ifM(nodeState(node).map(_ == NodeState.Suspicion).orElseSucceed(false))(
              changeNodeState(node, NodeState.Dead)
                .as(Message.Broadcast(Dead(node))),
              Message.noResponse
            ),
            timeout
          )
      }
    )

}
