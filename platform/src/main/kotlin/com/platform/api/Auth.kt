package com.platform.api

// Re-export from shared so existing callers are unaffected.
// AgentIdentity and JWT_AUTH live in com.platform.auth (shared module).
typealias AgentIdentity = com.platform.auth.AgentIdentity

const val JWT_AUTH = com.platform.auth.JWT_AUTH
