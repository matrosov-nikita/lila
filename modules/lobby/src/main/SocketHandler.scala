package lila.lobby

import akka.actor._
import akka.pattern.{ ask, pipe }
import play.api.libs.iteratee._
import play.api.libs.json._
import scala.concurrent.duration._

import actorApi._
import lila.common.PimpedJson._
import lila.hub.actorApi.lobby._
import lila.pool.{ PoolApi, PoolConfig }
import lila.rating.RatingRange
import lila.socket.actorApi.{ Connected => _, _ }
import lila.socket.Handler
import lila.user.User
import makeTimeout.short
import ornicar.scalalib.Zero

private[lobby] final class SocketHandler(
    hub: lila.hub.Env,
    lobby: ActorRef,
    socket: ActorRef,
    poolApi: PoolApi,
    blocking: String => Fu[Set[String]]) {

  private val HookPoolLimitPerMember = new lila.memo.RateLimit(
    credits = 25,
    duration = 1 minute,
    name = "lobby hook/pool per member",
    key = "lobby.hook_pool.member")

  private def HookPoolLimit[A: Zero](member: Member, cost: Int, msg: => String)(op: => A) =
    HookPoolLimitPerMember(
      k = member.uid,
      cost = cost,
      msg = s"$msg mobile=${member.mobile}")(op)

  private def controller(socket: ActorRef, member: Member): Handler.Controller = {
    case ("join", o) => HookPoolLimit(member, cost = 5, msg = s"join $o") {
      o str "d" foreach { id =>
        lobby ! BiteHook(id, member.uid, member.user)
      }
    }
    case ("cancel", _) => HookPoolLimit(member, cost = 1, msg = "cancel") {
      lobby ! CancelHook(member.uid)
    }
    case ("joinSeek", o) => HookPoolLimit(member, cost = 5, msg = s"joinSeek $o") {
      for {
        id <- o str "d"
        user <- member.user
      } lobby ! BiteSeek(id, user)
    }
    case ("cancelSeek", o) => HookPoolLimit(member, cost = 1, msg = s"cancelSeek $o") {
      for {
        id <- o str "d"
        user <- member.user
      } lobby ! CancelSeek(id, user)
    }
    case ("idle", o) => socket ! SetIdle(member.uid, ~(o boolean "d"))
    // entering a pool
    case ("poolIn", o) => HookPoolLimit(member, cost = 1, msg = s"poolIn $o") {
      for {
        user <- member.user
        d <- o obj "d"
        id <- d str "id"
        ratingRange = d str "range" flatMap RatingRange.apply
      } {
        lobby ! CancelHook(member.uid) // in case there's one...
        poolApi.join(
          PoolConfig.Id(id),
          PoolApi.Joiner(
            userId = user.id,
            socketId = lila.socket.Socket.Uid(member.uid),
            ratingMap = user.ratingMap,
            ratingRange = ratingRange,
            lame = user.lame,
            blocking = user.blocking))
      }
    }
    // leaving a pool
    case ("poolOut", o) => HookPoolLimit(member, cost = 1, msg = s"poolOut $o") {
      for {
        id <- o str "d"
        user <- member.user
      } poolApi.leave(PoolConfig.Id(id), user.id)
    }
    // entering the hooks view
    case ("hookIn", _) => HookPoolLimit(member, cost = 2, msg = "hookIn") {
      lobby ! HookSub(member, true)
    }
    // leaving the hooks view
    case ("hookOut", _) => socket ! HookSub(member, false)
  }

  def apply(uid: String, user: Option[User], mobile: Boolean): Fu[JsSocketHandler] =
    (user ?? (u => blocking(u.id))) flatMap { blockedUserIds =>
      val join = Join(uid = uid, user = user, blocking = blockedUserIds, mobile = mobile)
      Handler(hub, socket, uid, join) {
        case Connected(enum, member) =>
          (controller(socket, member), enum, member)
      }
    }
}
