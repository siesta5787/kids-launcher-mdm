package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/play-signin` (server v0.24.0+) - the one-time `oauth_token`
 * cookie Google's sign-in page set, see [com.kidslauncher.mdm.ui.PlaySignInActivity]. */
@Serializable
data class PlaySignInRequest(val oauthToken: String)

/** [message] is shown to the parent as-is, success or not. */
@Serializable
data class PlaySignInResponse(val ok: Boolean, val message: String)
