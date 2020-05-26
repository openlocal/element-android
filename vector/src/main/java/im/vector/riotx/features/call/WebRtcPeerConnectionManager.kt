/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.features.call

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.extensions.tryThis
import im.vector.matrix.android.api.session.call.CallsListener
import im.vector.matrix.android.api.session.call.EglUtils
import im.vector.matrix.android.api.session.room.model.call.CallAnswerContent
import im.vector.matrix.android.api.session.room.model.call.CallHangupContent
import im.vector.matrix.android.api.session.room.model.call.CallInviteContent
import im.vector.riotx.core.di.ActiveSessionHolder
import im.vector.riotx.features.call.service.CallHeadsUpService
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manage peerConnectionFactory & Peer connections outside of activity lifecycle to resist configuration changes
 * Use app context
 */
@Singleton
class WebRtcPeerConnectionManager @Inject constructor(
        private val context: Context,
        private val sessionHolder: ActiveSessionHolder
) : CallsListener {

    interface Listener {
        fun addLocalIceCandidate(candidates: IceCandidate)
        fun addRemoteVideoTrack(videoTrack: VideoTrack)
        fun addLocalVideoTrack(videoTrack: VideoTrack)
        fun removeRemoteVideoStream(mediaStream: MediaStream)
        fun onDisconnect()
        fun sendOffer(sessionDescription: SessionDescription)
    }

    var localMediaStream: MediaStream? = null

    var listener: Listener? = null

    // *Comments copied from webrtc demo app*
    // Executor thread is started once and is used for all
    // peer connection API calls to ensure new peer connection factory is
    // created on the same thread as previously destroyed factory.
    private val executor = Executors.newSingleThreadExecutor()

    private val rootEglBase by lazy { EglUtils.rootEglBase }

    private var peerConnectionFactory: PeerConnectionFactory? = null

    private var peerConnection: PeerConnection? = null

    private var localViewRenderer: SurfaceViewRenderer? = null
    private var remoteViewRenderer: SurfaceViewRenderer? = null

    private var remoteVideoTrack: VideoTrack? = null
    private var localVideoTrack: VideoTrack? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var videoCapturer: VideoCapturer? = null

    var localSurfaceRenderer: WeakReference<SurfaceViewRenderer>? = null
    var remoteSurfaceRenderer: WeakReference<SurfaceViewRenderer>? = null

    private val iceCandidateSource: PublishSubject<IceCandidate> = PublishSubject.create()
    private var iceCandidateDisposable: Disposable? = null

    var callHeadsUpService: CallHeadsUpService? = null

    private var callId: String? = null
    private var signalingRoomId: String? = null
    private var participantUserId: String? = null
    private var isVideoCall: Boolean? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            callHeadsUpService = (service as? CallHeadsUpService.CallHeadsUpServiceBinder)?.getService()
        }
    }

    private fun createPeerConnectionFactory() {
        if (peerConnectionFactory == null) {
            Timber.v("## VOIP createPeerConnectionFactory")
            val eglBaseContext = rootEglBase?.eglBaseContext ?: return Unit.also {
                Timber.e("## VOIP No EGL BASE")
            }

            Timber.v("## VOIP PeerConnectionFactory.initialize")
            PeerConnectionFactory.initialize(PeerConnectionFactory
                    .InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )

            val options = PeerConnectionFactory.Options()
            val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
                    eglBaseContext,
                    /* enableIntelVp8Encoder */
                    true,
                    /* enableH264HighProfile */
                    true)
            val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

            Timber.v("## VOIP PeerConnectionFactory.createPeerConnectionFactory ...")
            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setVideoEncoderFactory(defaultVideoEncoderFactory)
                    .setVideoDecoderFactory(defaultVideoDecoderFactory)
                    .createPeerConnectionFactory()
        }
    }

    private fun createPeerConnection(observer: PeerConnectionObserverAdapter) {
        val iceServers = ArrayList<PeerConnection.IceServer>().apply {
            listOf("turn:turn.matrix.org:3478?transport=udp", "turn:turn.matrix.org:3478?transport=tcp", "turns:turn.matrix.org:443?transport=tcp").forEach {
                add(
                        PeerConnection.IceServer.builder(it)
                                .setUsername("xxxxx")
                                .setPassword("xxxxx")
                                .createIceServer()
                )
            }
        }
        Timber.v("## VOIP creating peer connection... ")
        peerConnection = peerConnectionFactory?.createPeerConnection(iceServers, observer)
    }

    // TODO REMOVE THIS FUNCTION
    private fun createPeerConnection(videoCapturer: VideoCapturer) {
        executor.execute {
            Timber.v("## VOIP PeerConnectionFactory.createPeerConnection $peerConnectionFactory...")
            // Following instruction here: https://stackoverflow.com/questions/55085726/webrtc-create-peerconnectionfactory-object
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)

            videoSource = peerConnectionFactory?.createVideoSource(videoCapturer.isScreencast)
            Timber.v("## VOIP Local video source created")
            videoCapturer.initialize(surfaceTextureHelper, context.applicationContext, videoSource!!.capturerObserver)
            videoCapturer.startCapture(1280, 720, 30)

            localVideoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)?.also {
                Timber.v("## VOIP Local video track created")
//                localSurfaceRenderer?.get()?.let { surface ->
// //                    it.addSink(surface)
// //                }
            }

            // create a local audio track
            Timber.v("## VOIP create local audio track")
            audioSource = peerConnectionFactory?.createAudioSource(DEFAULT_AUDIO_CONSTRAINTS)
            audioTrack = peerConnectionFactory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)

//            pipRenderer.setMirror(true)
//            localVideoTrack?.addSink(pipRenderer)
//

//            val iceCandidateSource: PublishSubject<IceCandidate> = PublishSubject.create()
//
//            iceCandidateSource
//                    .buffer(400, TimeUnit.MILLISECONDS)
//                    .subscribe {
//                        // omit empty :/
//                        if (it.isNotEmpty()) {
//                            listener.addLocalIceCandidate()
//                            callViewModel.handle(VectorCallViewActions.AddLocalIceCandidate(it))
//                        }
//                    }
//                    .disposeOnDestroy()

            localMediaStream = peerConnectionFactory?.createLocalMediaStream("ARDAMS") // magic value?
            localMediaStream?.addTrack(localVideoTrack)
            localMediaStream?.addTrack(audioTrack)

//            val constraints = MediaConstraints()
//            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//            constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

            Timber.v("## VOIP add local stream to peer connection")
            peerConnection?.addStream(localMediaStream)
        }
    }

    private fun startCall() {
        createPeerConnectionFactory()
        createPeerConnection(object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(p0: IceCandidate?) {
                Timber.v("## VOIP onIceCandidate local $p0")
                p0?.let { iceCandidateSource.onNext(it) }
            }

            override fun onAddStream(mediaStream: MediaStream?) {
                Timber.v("## VOIP onAddStream remote $mediaStream")
                mediaStream?.videoTracks?.firstOrNull()?.let {
                    remoteVideoTrack = it
                    remoteSurfaceRenderer?.get()?.let { surface ->
                        it.setEnabled(true)
                        it.addSink(surface)
                    }
                    mediaStream.videoTracks?.firstOrNull()?.let { videoTrack ->
                        remoteVideoTrack = videoTrack
                        remoteVideoTrack?.setEnabled(true)
                        remoteVideoTrack?.addSink(remoteViewRenderer)
                    }
                }
            }

            override fun onRemoveStream(mediaStream: MediaStream?) {
                mediaStream?.let {
                    listener?.removeRemoteVideoStream(it)
                }
                remoteSurfaceRenderer?.get()?.let {
                    remoteVideoTrack?.removeSink(it)
                }
                remoteVideoTrack = null
            }

            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                Timber.v("## VOIP onIceConnectionChange $p0")
                if (p0 == PeerConnection.IceConnectionState.DISCONNECTED) {
                    listener?.onDisconnect()
                }
            }
        })

        iceCandidateDisposable = iceCandidateSource
                .buffer(400, TimeUnit.MILLISECONDS)
                .subscribe {
                    // omit empty :/
                    if (it.isNotEmpty()) {
                        Timber.v("## Sending local ice candidates to callId: $callId roomId: $signalingRoomId")
                        sessionHolder
                                .getActiveSession()
                                .callService()
                                .sendLocalIceCandidates(callId ?: "", signalingRoomId ?: "", it)
                    }
                }
    }

    private fun sendSdpOffer() {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))

        Timber.v("## VOIP creating offer...")
        peerConnection?.createOffer(object : SdpObserver {
            override fun onSetFailure(p0: String?) {
                Timber.v("## VOIP onSetFailure $p0")
            }

            override fun onSetSuccess() {
                Timber.v("## VOIP onSetSuccess")
            }

            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Timber.v("## VOIP onCreateSuccess $sessionDescription will set local description")
                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Timber.v("## setLocalDescription success")
                        callId = UUID.randomUUID().toString()
                        Timber.v("## sending offer to callId: $callId roomId: $signalingRoomId")
                        sessionHolder.getActiveSession().callService().sendOfferSdp(callId ?: "", signalingRoomId
                                ?: "", sessionDescription, object : MatrixCallback<String> {})
                    }
                }, sessionDescription)
            }

            override fun onCreateFailure(p0: String?) {
                Timber.v("## VOIP onCreateFailure $p0")
            }
        }, constraints)
    }

    fun attachViewRenderers(localViewRenderer: SurfaceViewRenderer, remoteViewRenderer: SurfaceViewRenderer) {
        this.localViewRenderer = localViewRenderer
        this.remoteViewRenderer = remoteViewRenderer
        audioSource = peerConnectionFactory?.createAudioSource(DEFAULT_AUDIO_CONSTRAINTS)
        audioTrack = peerConnectionFactory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)

        localViewRenderer.setMirror(true)
        localVideoTrack?.addSink(localViewRenderer)

        localMediaStream = peerConnectionFactory?.createLocalMediaStream("ARDAMS") // magic value?

        if (isVideoCall == true) {
            val cameraIterator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
            val frontCamera = cameraIterator.deviceNames
                    ?.firstOrNull { cameraIterator.isFrontFacing(it) }
                    ?: cameraIterator.deviceNames?.first()

            val videoCapturer = cameraIterator.createCapturer(frontCamera, null)

            videoSource = peerConnectionFactory?.createVideoSource(videoCapturer.isScreencast)
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
            Timber.v("## VOIP Local video source created")
            videoCapturer.initialize(surfaceTextureHelper, context.applicationContext, videoSource!!.capturerObserver)
            videoCapturer.startCapture(1280, 720, 30)
            localVideoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)?.also {
                Timber.v("## VOIP Local video track created")
                localSurfaceRenderer?.get()?.let { surface ->
                    it.addSink(surface)
                }
            }
            localMediaStream?.addTrack(localVideoTrack)
        }

        localVideoTrack?.addSink(localViewRenderer)
        remoteVideoTrack?.let {
            it.setEnabled(true)
            it.addSink(remoteViewRenderer)
        }
        localMediaStream?.addTrack(audioTrack)

        Timber.v("## VOIP add local stream to peer connection")
        peerConnection?.addStream(localMediaStream)

        localSurfaceRenderer = WeakReference(localViewRenderer)
        remoteSurfaceRenderer = WeakReference(remoteViewRenderer)
    }

    fun detachRenderers() {
        localSurfaceRenderer?.get()?.let {
            localVideoTrack?.removeSink(it)
        }
        remoteSurfaceRenderer?.get()?.let {
            remoteVideoTrack?.removeSink(it)
        }
        localSurfaceRenderer = null
        remoteSurfaceRenderer = null
    }

    fun close() {
        executor.execute {
            // Do not dispose peer connection (https://bugs.chromium.org/p/webrtc/issues/detail?id=7543)
            tryThis { audioSource?.dispose() }
            tryThis { videoSource?.dispose() }
            tryThis { videoCapturer?.stopCapture() }
            tryThis { videoCapturer?.dispose() }
            localMediaStream?.let { peerConnection?.removeStream(it) }
            peerConnection?.close()
            peerConnection = null
            peerConnectionFactory?.stopAecDump()
            peerConnectionFactory = null
        }
        iceCandidateDisposable?.dispose()
        context.stopService(Intent(context, CallHeadsUpService::class.java))
    }

    companion object {

        private const val AUDIO_TRACK_ID = "ARDAMSa0"

        private val DEFAULT_AUDIO_CONSTRAINTS = MediaConstraints().apply {
            // add all existing audio filters to avoid having echos
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googDAEchoCancellation", "true"))

            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))

            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))

            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))

            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
    }

    fun startOutgoingCall(context: Context, signalingRoomId: String, participantUserId: String, isVideoCall: Boolean) {
        this.signalingRoomId = signalingRoomId
        this.participantUserId = participantUserId
        this.isVideoCall = isVideoCall

        startHeadsUpService(signalingRoomId, sessionHolder.getActiveSession().myUserId, false, isVideoCall)
        context.startActivity(VectorCallActivity.newIntent(context, signalingRoomId, participantUserId, false, isVideoCall))

        startCall()
        sendSdpOffer()
    }

    override fun onCallInviteReceived(signalingRoomId: String, participantUserId: String, callInviteContent: CallInviteContent) {
        this.callId = callInviteContent.callId
        this.signalingRoomId = signalingRoomId
        this.participantUserId = participantUserId
        this.isVideoCall = callInviteContent.isVideo()

        startHeadsUpService(signalingRoomId, participantUserId, true, callInviteContent.isVideo())
        context.startActivity(VectorCallActivity.newIntent(context, signalingRoomId, participantUserId, false, callInviteContent.isVideo()))

        startCall()
    }

    private fun startHeadsUpService(roomId: String, participantUserId: String, isIncomingCall: Boolean, isVideoCall: Boolean) {
        val callHeadsUpServiceIntent = CallHeadsUpService.newInstance(context, roomId, participantUserId, isIncomingCall, isVideoCall)
        ContextCompat.startForegroundService(context, callHeadsUpServiceIntent)

        context.bindService(Intent(context, CallHeadsUpService::class.java), serviceConnection, 0)
    }

    fun endCall() {
        if (callId != null && signalingRoomId != null) {
            sessionHolder.getActiveSession().callService().sendHangup(callId!!, signalingRoomId!!)
        }
        close()
    }

    override fun onCallAnswerReceived(callAnswerContent: CallAnswerContent) {
        this.callId = callAnswerContent.callId

        executor.execute {
            Timber.v("## answerReceived $callId")
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, callAnswerContent.answer.sdp)
            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {}, sdp)
        }
    }

    override fun onCallHangupReceived(callHangupContent: CallHangupContent) {
        close()
    }
}
