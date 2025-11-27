package no.nav.syfo.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

val superviserDispacherIO = CoroutineScope(SupervisorJob() + Dispatchers.IO)
