package com.geonotes.backend

import com.geonotes.backend.auth.DevTokenVerifier
import com.geonotes.backend.auth.DisabledPubSubTokenVerifier
import com.geonotes.backend.auth.GooglePubSubTokenVerifier
import com.geonotes.backend.auth.PubSubTokenVerifier
import com.geonotes.backend.auth.FirebaseTokenVerifier
import com.geonotes.backend.auth.TokenVerifier
import com.geonotes.backend.billing.GooglePlayPurchaseVerifier
import com.geonotes.backend.billing.PlayPurchaseVerifier
import com.geonotes.backend.billing.ServiceAccountAccessTokenProvider
import com.geonotes.backend.billing.StubPlayPurchaseVerifier
import com.geonotes.backend.config.AppConfig
import com.geonotes.backend.config.AuthMode
import com.geonotes.backend.controllers.DevicesController
import com.geonotes.backend.controllers.EntitlementsController
import com.geonotes.backend.controllers.EventsController
import com.geonotes.backend.controllers.FriendsController
import com.geonotes.backend.controllers.MeController
import com.geonotes.backend.controllers.PlayNotificationsController
import com.geonotes.backend.controllers.ShareRequestsController
import com.geonotes.backend.controllers.SharesController
import com.geonotes.backend.domain.service.DeviceService
import com.geonotes.backend.domain.service.EntitlementService
import com.geonotes.backend.domain.service.EventService
import com.geonotes.backend.domain.service.FriendService
import com.geonotes.backend.domain.service.ShareRequestService
import com.geonotes.backend.domain.service.ShareService
import com.geonotes.backend.domain.service.UserService
import com.geonotes.backend.persistence.DatabaseHealth
import com.geonotes.backend.persistence.ExposedDeviceRepository
import com.geonotes.backend.persistence.ExposedEntitlementRepository
import com.geonotes.backend.persistence.ExposedEventRepository
import com.geonotes.backend.persistence.ExposedFriendshipRepository
import com.geonotes.backend.persistence.ExposedInviteRepository
import com.geonotes.backend.persistence.ExposedShareRepository
import com.geonotes.backend.persistence.ExposedShareRequestRepository
import com.geonotes.backend.persistence.ExposedTransactionRunner
import com.geonotes.backend.persistence.ExposedUserRepository
import com.geonotes.backend.push.FcmPushSender
import com.geonotes.backend.push.LoggingPushSender
import com.geonotes.backend.push.PushSender
import com.google.firebase.FirebaseApp
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.Closeable
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset

/**
 * Composition root (manual DI). Wires: routes → controllers → domain services → repository
 * interfaces ← Exposed implementations. Tests build it with fakes for the edge adapters.
 */
class AppModule(
    val config: AppConfig,
    database: Database,
    val tokenVerifier: TokenVerifier,
    pushSender: PushSender,
    private val purchaseVerifier: PlayPurchaseVerifier,
    /** Millisecond ticks: stable round-trips through PostgreSQL (µs precision). */
    val clock: Clock = Clock.tickMillis(ZoneOffset.UTC),
    /** Authenticates Google Play RTDN pushes (POST /v1/play/rtdn). Rejects everything unless RTDN is configured. */
    val pubSubTokenVerifier: PubSubTokenVerifier = DisabledPubSubTokenVerifier,
) : Closeable {
    // Model: persistence
    private val tx = ExposedTransactionRunner(database)
    private val userRepository = ExposedUserRepository(database)
    private val deviceRepository = ExposedDeviceRepository(database)
    private val inviteRepository = ExposedInviteRepository(database)
    private val friendshipRepository = ExposedFriendshipRepository(database)
    private val shareRepository = ExposedShareRepository(database)
    private val shareRequestRepository = ExposedShareRequestRepository(database)
    private val eventRepository = ExposedEventRepository(database)
    private val entitlementRepository = ExposedEntitlementRepository(database)
    val databaseHealth = DatabaseHealth(database)

    // Model: domain services
    val userService = UserService(userRepository, entitlementRepository, clock)
    val deviceService = DeviceService(deviceRepository, userService, clock)
    val friendService = FriendService(
        users = userRepository,
        userService = userService,
        invites = inviteRepository,
        friendships = friendshipRepository,
        shares = shareRepository,
        shareRequests = shareRequestRepository,
        devices = deviceRepository,
        tx = tx,
        clock = clock,
        inviteTtl = config.inviteTtl,
    )
    val shareService = ShareService(
        shares = shareRepository,
        devices = deviceRepository,
        friendService = friendService,
        userService = userService,
        tx = tx,
        clock = clock,
        maxActiveSharesPerOwner = config.maxActiveSharesPerOwner,
    )
    val shareRequestService = ShareRequestService(
        requests = shareRequestRepository,
        devices = deviceRepository,
        friendService = friendService,
        shareService = shareService,
        userService = userService,
        pushSender = pushSender,
        tx = tx,
        clock = clock,
        ttl = config.shareRequestTtl,
    )
    val eventService = EventService(shareService, eventRepository, deviceRepository, inviteRepository, pushSender, clock, config.eventTtl)
    val entitlementService = EntitlementService(
        verifier = purchaseVerifier,
        entitlements = entitlementRepository,
        userService = userService,
        clock = clock,
        packageName = config.play.packageName,
        allowTestPurchases = config.play.allowTestPurchases,
        reverifyAfter = config.play.reverifyAfter,
    )

    // ViewModels
    val meController = MeController(userService, clock)
    val devicesController = DevicesController(deviceService)
    val friendsController = FriendsController(friendService)
    val sharesController = SharesController(shareService)
    val shareRequestsController = ShareRequestsController(shareRequestService)
    val eventsController = EventsController(eventService)
    val entitlementsController = EntitlementsController(entitlementService, clock)
    val playNotificationsController = PlayNotificationsController(entitlementService)

    override fun close() {
        (purchaseVerifier as? Closeable)?.close()
    }

    companion object {
        /** Production wiring from config: picks Firebase or dev auth and FCM or logging push. */
        fun create(config: AppConfig, database: Database, firebaseApp: FirebaseApp?, clock: Clock = Clock.tickMillis(ZoneOffset.UTC)): AppModule {
            val tokenVerifier = when (config.authMode) {
                AuthMode.DEV -> DevTokenVerifier()
                AuthMode.FIREBASE -> FirebaseTokenVerifier(
                    firebaseApp ?: error("AUTH_MODE=firebase requires Firebase credentials (GOOGLE_APPLICATION_CREDENTIALS)"),
                )
            }
            val pushSender = firebaseApp?.let { FcmPushSender(it) } ?: LoggingPushSender()
            return AppModule(config, database, tokenVerifier, pushSender, purchaseVerifier(config, clock), clock, pubSubVerifier(config))
        }

        /** AUTH_MODE=dev → stub (never talks to Google); otherwise the real Play Developer API verifier. */
        fun purchaseVerifier(config: AppConfig, clock: Clock): PlayPurchaseVerifier = when (config.authMode) {
            AuthMode.DEV -> StubPlayPurchaseVerifier(clock, allowTestTokens = true)
            AuthMode.FIREBASE -> GooglePlayPurchaseVerifier(
                packageName = config.play.packageName,
                accessTokens = try {
                    ServiceAccountAccessTokenProvider.fromFile(config.play.serviceAccountJsonPath)
                } catch (e: IOException) {
                    error("Play billing needs a service account (PLAY_SERVICE_ACCOUNT_JSON or GOOGLE_APPLICATION_CREDENTIALS): ${e.message}")
                },
            )
        }

        fun pubSubVerifier(config: AppConfig): PubSubTokenVerifier {
            val audience = config.rtdn.audience
            val email = config.rtdn.pushServiceAccount
            return if (config.rtdn.enabled && audience != null && email != null) {
                GooglePubSubTokenVerifier(audience, email)
            } else {
                DisabledPubSubTokenVerifier
            }
        }
    }
}
