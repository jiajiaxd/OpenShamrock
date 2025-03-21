@file:OptIn(DelicateCoroutinesApi::class)

package moe.fuqiuluo.qqinterface.servlet

import android.os.Bundle
import com.tencent.mobileqq.app.QQAppInterface
import com.tencent.mobileqq.msf.core.MsfCore
import com.tencent.mobileqq.pb.ByteStringMicro
import com.tencent.qphone.base.remote.ToServiceMsg
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.fuqiuluo.proto.protobufOf
import moe.fuqiuluo.shamrock.utils.PlatformUtils
import moe.fuqiuluo.shamrock.xposed.helper.AppRuntimeFetcher
import moe.fuqiuluo.shamrock.xposed.helper.PacketHandler
import moe.fuqiuluo.shamrock.xposed.helper.internal.DynamicReceiver
import moe.fuqiuluo.shamrock.xposed.helper.internal.IPCRequest
import mqq.app.MobileQQ
import tencent.im.oidb.oidb_sso
import kotlin.concurrent.timer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal abstract class BaseSvc {
    companion object {
        val currentUin: String
            get() = app.currentAccountUin

        val app: QQAppInterface
            get() = AppRuntimeFetcher.appRuntime as QQAppInterface

        fun createToServiceMsg(cmd: String): ToServiceMsg {
            return ToServiceMsg("mobileqq.service", app.currentAccountUin, cmd)
        }

        suspend fun sendOidbAW(cmd: String, cmdId: Int, serviceId: Int, data: ByteArray, trpc: Boolean = false, timeout: Long = 5000L): ByteArray? {
            return withTimeoutOrNull(timeout) {
                suspendCoroutine { continuation ->
                    val seq = MsfCore.getNextSeq()
                    GlobalScope.launch(Dispatchers.Default) {
                        DynamicReceiver.register(IPCRequest(cmd, seq) {
                            val buffer = it.getByteArrayExtra("buffer")!!
                            continuation.resume(buffer)
                        })
                    }
                    if (trpc) sendTrpcOidb(cmd, cmdId, serviceId, data, seq)
                    else sendOidb(cmd, cmdId, serviceId, data, seq)
                }
            }?.copyOf()
        }

        suspend fun sendBufferAW(cmd: String, isPb: Boolean, data: ByteArray, timeout: Long = 5000L): ByteArray? {
            return withTimeoutOrNull<ByteArray?>(timeout) {
                suspendCoroutine { continuation ->
                    val seq = MsfCore.getNextSeq()
                    GlobalScope.launch(Dispatchers.Default) {
                        DynamicReceiver.register(IPCRequest(cmd, seq) {
                            val buffer = it.getByteArrayExtra("buffer")!!
                            continuation.resume(buffer)
                        })
                        sendBuffer(cmd, isPb, data, seq)
                    }
                }
            }?.copyOf()
        }

        fun sendOidb(cmd: String, cmdId: Int, serviceId: Int, buffer: ByteArray, seq: Int = -1, trpc: Boolean = false) {
            if (trpc) {
                sendTrpcOidb(cmd, cmdId, serviceId, buffer, seq)
                return
            }
            val to = createToServiceMsg(cmd)
            val oidb = oidb_sso.OIDBSSOPkg()
            oidb.uint32_command.set(cmdId)
            oidb.uint32_service_type.set(serviceId)
            oidb.bytes_bodybuffer.set(ByteStringMicro.copyFrom(buffer))
            oidb.str_client_version.set(PlatformUtils.getClientVersion(MobileQQ.getContext()))
            to.putWupBuffer(oidb.toByteArray())
            to.addAttribute("req_pb_protocol_flag", true)
            if (seq != -1) {
                to.addAttribute("shamrock_seq", seq)
            }
            app.sendToService(to)
        }

        fun sendTrpcOidb(cmd: String, cmdId: Int, serviceId: Int, buffer: ByteArray, seq: Int = -1) {
            val to = createToServiceMsg(cmd)
            to.putWupBuffer(protobufOf(
                1 to cmdId,
                2 to serviceId,
                4 to buffer,
                12 to 0
            ).toByteArray())
            to.addAttribute("req_pb_protocol_flag", true)
            if (seq != -1) {
                to.addAttribute("shamrock_seq", seq)
            }
            app.sendToService(to)
        }

        fun sendBuffer(cmd: String, isPb: Boolean, buffer: ByteArray, seq: Int) {
            val toServiceMsg = ToServiceMsg("mobileqq.service", app.currentUin, cmd)
            toServiceMsg.putWupBuffer(buffer)
            toServiceMsg.addAttribute("req_pb_protocol_flag", isPb)
            toServiceMsg.addAttribute("shamrock_seq", seq)
            app.sendToService(toServiceMsg)
        }
    }

    protected fun send(toServiceMsg: ToServiceMsg) {
        app.sendToService(toServiceMsg)
    }

    protected fun sendExtra(cmd: String, builder: (Bundle) -> Unit) {
        val toServiceMsg = createToServiceMsg(cmd)
        builder(toServiceMsg.extraData)
        app.sendToService(toServiceMsg)
    }

    protected fun sendPb(cmd: String, buffer: ByteArray, seq: Int) {
        val toServiceMsg = createToServiceMsg(cmd)
        toServiceMsg.putWupBuffer(buffer)
        toServiceMsg.addAttribute("req_pb_protocol_flag", true)
        toServiceMsg.addAttribute("shamrock_seq", seq)
        app.sendToService(toServiceMsg)
    }
}