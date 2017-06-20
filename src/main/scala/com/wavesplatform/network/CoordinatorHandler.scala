package com.wavesplatform.network

import com.wavesplatform.Coordinator
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import scorex.block.Block
import scorex.transaction.ValidationError
import scorex.utils.ScorexLogging

@Sharable
class CoordinatorHandler(coordinator: Coordinator, blacklister: Blacklister, broadcaster: Broadcaster)
  extends ChannelInboundHandlerAdapter with ScorexLogging {
  import CoordinatorHandler._
  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) = {
    log.debug(s"${id(ctx)} removed: ${ctx.isRemoved}, active: ${ctx.channel().isActive}, open: ${ctx.channel().isOpen}")
    msg match {
      case c: Checkpoint =>
        handleResult(ctx, blacklister.blacklist, "applying checkpoint", coordinator.processCheckpoint(c))
      case ExtensionBlocks(blocks) =>
        handleResult(ctx, blacklister.blacklist, "processing fork", coordinator.processFork(blocks.head.reference, blocks))
      case b: Block =>
        handleResult(ctx, blacklister.blacklist, "applying block", coordinator.processBlock(b))
      case BlockForged(b) =>
        handleResult(ctx, blacklister.blacklist, "applying locally mined block", coordinator.processBlock(b))
        broadcaster.broadcast(b, None)
      case other =>
        log.debug(other.getClass.getCanonicalName)
    }
  }
}

object CoordinatorHandler extends ScorexLogging {
  private[CoordinatorHandler] def handleResult(
      ctx: ChannelHandlerContext,
      blacklist: Channel => Unit,
      msg: String,
      f: => Either[ValidationError, BigInt]): Either[ValidationError, BigInt] = {
    log.debug(s"${id(ctx)} Starting $msg")
    val result = f
    result match {
      case Left(error) =>
        log.warn(s"${id(ctx)} Error $msg: $error")
        blacklist(ctx.channel())
      case Right(newScore) =>
        log.debug(s"${id(ctx)} Finished $msg, new local score is $newScore")
        ctx.writeAndFlush(LocalScoreChanged(newScore))
    }
    result
  }
}
