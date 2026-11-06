package com.smaznet.teleplay.ui.proxy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.smaznet.teleplay.R
import com.smaznet.teleplay.domain.proxy.AddResult
import com.smaznet.teleplay.domain.proxy.EnableResult
import com.smaznet.teleplay.domain.proxy.FormError
import com.smaznet.teleplay.domain.proxy.FormResult
import com.smaznet.teleplay.domain.proxy.HealthProgress
import com.smaznet.teleplay.domain.proxy.MtSecret
import com.smaznet.teleplay.domain.proxy.Proxy
import com.smaznet.teleplay.domain.proxy.ProxyForm
import com.smaznet.teleplay.domain.proxy.ProxyFormLogic
import com.smaznet.teleplay.domain.proxy.ProxyHealthCheck
import com.smaznet.teleplay.domain.proxy.ProxyKind
import com.smaznet.teleplay.domain.proxy.ProxyRepository
import com.smaznet.teleplay.domain.proxy.ProxyTestResult
import com.smaznet.teleplay.domain.proxy.ProxyTesting
import com.smaznet.teleplay.domain.proxy.SecretKind
import com.smaznet.teleplay.ui.common.ConfirmDialog
import com.smaznet.teleplay.ui.common.HeroButton
import com.smaznet.teleplay.ui.common.HeroChip
import com.smaznet.teleplay.ui.common.HeroTone
import com.smaznet.teleplay.ui.common.heroPanel
import com.smaznet.teleplay.ui.common.requestSafely
import com.smaznet.teleplay.ui.theme.HeroDangerSoft
import com.smaznet.teleplay.ui.theme.HeroDivider
import com.smaznet.teleplay.ui.theme.HeroRadiusMd
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private sealed interface Editing {
    data object New : Editing
    class Existing(val id: Long) : Editing
}

/**
 * Proxies: a health check above the list, the saved proxies, and an add/edit form. Initial focus: the first proxy,
 * or "Add proxy" when the list is empty. Back closes the form, or stops a running check, before leaving.
 * Nothing here logs; secrets and passwords are masked until the viewer shows them.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxyScreen(
    repository: ProxyRepository,
    tester: ProxyTesting,
    health: ProxyHealthCheck,
    modifier: Modifier = Modifier,
    startWithAdd: Boolean = false,
    /** During setup the viewer has no working route yet, so a proxy added here is also switched on. */
    enableOnAdd: Boolean = false,
) {
    val proxies by repository.proxies.collectAsState()
    val state by repository.state.collectAsState()
    var editing by remember { mutableStateOf<Editing?>(if (startWithAdd) Editing.New else null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    var testing by remember { mutableStateOf(setOf<Long>()) }
    var progress by remember { mutableStateOf<HealthProgress?>(null) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var checkStarted by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun stopCheck() { checkJob?.cancel(); checkJob = null; progress = null }

    fun startCheck() {
        stopCheck()
        checkStarted = System.currentTimeMillis()
        val job = scope.launch {
            try {
                health.run().collect { progress = it }
            } finally {
                checkJob = null
            }
        }
        checkJob = job
    }

    fun testOne(p: Proxy) {
        if (p.id in testing) return
        testing = testing + p.id
        scope.launch {
            try { repository.recordTest(p.id, tester.test(p)) } finally { testing = testing - p.id }
        }
    }

    BackHandler(enabled = editing != null) { editing = null }
    BackHandler(enabled = editing == null && checkJob != null) { stopCheck() }

    if (editing != null) {
        val target = editing!!
        val existing = (target as? Editing.Existing)?.let { repository.get(it.id) }
        ProxyFormPane(
            existing = existing,
            repository = repository,
            onClose = { editing = null },
            enableOnAdd = enableOnAdd,
            modifier = modifier,
        )
        return
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestSafely() }
    val running = checkJob != null

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(48.dp).width(760.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.proxy_title), style = MaterialTheme.typography.headlineLarge)

            var needSelect by remember { mutableStateOf(false) }
            HeroButton(
                onClick = {
                    needSelect = false
                    if (state.enabled) repository.setEnabled(false)
                    else if (repository.setEnabled(true) == EnableResult.NeedsProxy) needSelect = true
                },
                modifier = Modifier.fillMaxWidth().testTag("proxy_toggle"),
            ) {
                Text(
                    stringResource(R.string.proxy_use) + ": " + stringResource(if (state.active) R.string.proxy_switch_on else R.string.proxy_switch_off),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (needSelect) Text(stringResource(R.string.proxy_need_select), color = HeroDangerSoft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("proxy_need_select"))

            // Health check, above the list.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                HeroButton(
                    onClick = { if (running) stopCheck() else startCheck() },
                    enabled = proxies.isNotEmpty(),
                    modifier = Modifier.testTag("proxy_check_all"),
                ) { Text(stringResource(if (running) R.string.proxy_check_stop else R.string.proxy_check_all)) }
                val p = progress
                Text(
                    when {
                        proxies.isEmpty() -> ""
                        p == null -> stringResource(R.string.proxy_check_hint)
                        p.done -> stringResource(R.string.proxy_check_summary, p.working, p.total)
                        else -> stringResource(R.string.proxy_check_running, p.checked, p.total)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("proxy_check_summary"),
                )
            }

            if (proxies.isEmpty()) {
                Text(stringResource(R.string.proxy_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            proxies.forEachIndexed { i, p ->
                ProxyRow(
                    proxy = p,
                    selected = state.selectedId == p.id,
                    inUse = state.active && state.selectedId == p.id,
                    testing = p.id in testing || (running && p.lastTestAt < checkStarted),
                    onClick = { menuFor = p.id },
                    modifier = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier).testTag("proxy_row_$i"),
                )
            }
            HeroButton(
                onClick = { editing = Editing.New },
                solid = proxies.isEmpty(),
                modifier = (if (proxies.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier).fillMaxWidth().testTag("proxy_add"),
            ) { Text(stringResource(R.string.proxy_add)) }
        }
    }

    menuFor?.let { id ->
        val p = repository.get(id)
        if (p == null) menuFor = null else ProxyActions(
            proxy = p,
            onSelect = { repository.select(p.id); menuFor = null },
            onTest = { menuFor = null; testOne(p) },
            onEdit = { menuFor = null; editing = Editing.Existing(p.id) },
            onDelete = { menuFor = null; deleteId = p.id },
            onClose = { menuFor = null },
        )
    }
    deleteId?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.proxy_delete_title),
            message = stringResource(R.string.proxy_delete_message),
            confirmLabel = stringResource(R.string.proxy_action_delete),
            onConfirm = { repository.delete(id); deleteId = null },
            onDismiss = { deleteId = null },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxyRow(proxy: Proxy, selected: Boolean, inUse: Boolean, testing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    HeroButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(proxy.label, style = MaterialTheme.typography.titleMedium)
            Text("${kindLabel(proxy)}  ·  ${proxy.server}:${proxy.port}", style = MaterialTheme.typography.bodySmall)
            Text(if (testing) stringResource(R.string.proxy_testing) else resultText(proxy.lastTest), style = MaterialTheme.typography.bodySmall)
        }
        if (inUse) HeroChip(stringResource(R.string.proxy_in_use), tone = HeroTone.Primary)
        else if (selected) HeroChip(stringResource(R.string.proxy_selected))
    }
}

@Composable
private fun kindLabel(p: Proxy): String = when (val s = p.secret) {
    null -> stringResource(R.string.proxy_kind_socks5)
    is MtSecret.Plain -> stringResource(R.string.proxy_kind_mtproto)
    is MtSecret.Padded -> stringResource(R.string.proxy_kind_mtproto_padded)
    is MtSecret.FakeTls -> stringResource(R.string.proxy_kind_mtproto_tls)
}

/** The result of a test in plain language; shared with the Home switch. */
@Composable
fun resultText(r: ProxyTestResult?): String = when (r) {
    null -> stringResource(R.string.proxy_result_untested)
    is ProxyTestResult.Working -> stringResource(R.string.proxy_result_working, r.latencyMs.toInt())
    ProxyTestResult.AuthRejected -> stringResource(R.string.proxy_result_auth)
    ProxyTestResult.SecretRejected -> stringResource(R.string.proxy_result_secret)
    ProxyTestResult.NotReachable -> stringResource(R.string.proxy_result_unreachable)
    ProxyTestResult.TimedOut -> stringResource(R.string.proxy_result_timeout)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxyActions(proxy: Proxy, onSelect: () -> Unit, onTest: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(520.dp).heroPanel().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(proxy.label, style = MaterialTheme.typography.headlineSmall)
            HeroButton(onSelect, solid = true, modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("proxy_action_select")) { Text(stringResource(R.string.proxy_action_select)) }
            HeroButton(onTest, modifier = Modifier.fillMaxWidth().testTag("proxy_action_test")) { Text(stringResource(R.string.proxy_action_test)) }
            HeroButton(onEdit, modifier = Modifier.fillMaxWidth().testTag("proxy_action_edit")) { Text(stringResource(R.string.proxy_action_edit)) }
            HeroButton(onDelete, tone = HeroTone.Danger, modifier = Modifier.fillMaxWidth().testTag("proxy_action_delete")) { Text(stringResource(R.string.proxy_action_delete)) }
            HeroButton(onClose, modifier = Modifier.fillMaxWidth().testTag("proxy_action_close")) { Text(stringResource(R.string.proxy_action_close)) }
        }
    }
    LaunchedEffect(Unit) { first.requestSafely() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxyFormPane(existing: Proxy?, repository: ProxyRepository, onClose: () -> Unit, enableOnAdd: Boolean, modifier: Modifier) {
    var form by remember { mutableStateOf(existing?.let { ProxyFormLogic.fromDraft(com.smaznet.teleplay.domain.proxy.ProxyDraft(it.kind, it.server, it.port, it.name, it.username, it.password, it.secret)) } ?: ProxyForm()) }
    var link by remember { mutableStateOf("") }
    var linkBad by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<FormError?>(null) }
    var addError by remember { mutableStateOf<Int?>(null) }
    var show by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestSafely() }

    fun save() {
        when (val r = ProxyFormLogic.toDraft(form)) {
            is FormResult.Bad -> { error = r.error; addError = null }
            is FormResult.Ok -> {
                val res = if (existing == null) repository.add(r.draft) else repository.update(existing.id, r.draft)
                when (res) {
                    is AddResult.Added -> {
                        if (existing == null && (enableOnAdd || repository.state.value.selectedId == null)) repository.select(res.proxy.id)
                        if (existing == null && enableOnAdd) repository.setEnabled(true)
                        onClose()
                    }
                    is AddResult.Duplicate -> { error = null; addError = R.string.proxy_error_duplicate }
                    AddResult.LimitReached -> { error = null; addError = R.string.proxy_error_limit }
                    is AddResult.Invalid -> { error = FormError.SERVER; addError = null }
                }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(48.dp).width(760.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(if (existing == null) R.string.proxy_form_add_title else R.string.proxy_form_edit_title), style = MaterialTheme.typography.headlineLarge)
            HeroButton(
                onClick = { form = form.copy(kind = if (form.kind == ProxyKind.SOCKS5) ProxyKind.MTPROTO else ProxyKind.SOCKS5); error = null },
                modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("proxy_form_type"),
            ) {
                Text(stringResource(R.string.proxy_form_type) + ": " + stringResource(if (form.kind == ProxyKind.SOCKS5) R.string.proxy_kind_socks5 else R.string.proxy_kind_mtproto))
            }
            if (existing == null) {
                ProxyField(link, { v ->
                    link = v
                    val f = ProxyFormLogic.fromLink(v)
                    linkBad = v.isNotBlank() && f == null
                    if (f != null) { form = f; error = null }
                }, stringResource(R.string.proxy_form_link), "proxy_field_link")
                if (linkBad) Text(stringResource(R.string.proxy_form_link_bad), color = HeroDangerSoft, style = MaterialTheme.typography.bodyMedium)
            }
            ProxyField(form.server, { form = form.copy(server = it.trim()); error = null }, stringResource(R.string.proxy_form_server), "proxy_field_server", KeyboardType.Uri)
            ProxyField(form.port, { form = form.copy(port = it.filter(Char::isDigit).take(5)); error = null }, stringResource(R.string.proxy_form_port), "proxy_field_port", KeyboardType.Number)
            if (form.kind == ProxyKind.SOCKS5) {
                ProxyField(form.username, { form = form.copy(username = it); error = null }, stringResource(R.string.proxy_form_user), "proxy_field_user")
                ProxyField(form.password, { form = form.copy(password = it); error = null }, stringResource(R.string.proxy_form_pass), "proxy_field_pass", KeyboardType.Password, mask = !show)
            } else {
                ProxyField(form.secret, { form = form.copy(secret = it.trim()); error = null }, stringResource(R.string.proxy_form_secret), "proxy_field_secret", KeyboardType.Password, mask = !show)
                val kind = ProxyFormLogic.secretKind(form.secret)
                if (kind != null) {
                    val label = stringResource(when (kind) {
                        SecretKind.PLAIN -> R.string.proxy_kind_mtproto
                        SecretKind.PADDED -> R.string.proxy_kind_mtproto_padded
                        SecretKind.FAKE_TLS -> R.string.proxy_kind_mtproto_tls
                    })
                    val domain = ProxyFormLogic.coverDomain(form.secret)
                    Text(
                        if (domain != null) stringResource(R.string.proxy_form_detected_domain, label, domain) else stringResource(R.string.proxy_form_detected, label),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("proxy_secret_kind"),
                    )
                }
            }
            ProxyField(form.name, { form = form.copy(name = it); error = null }, stringResource(R.string.proxy_form_name), "proxy_field_name")
            HeroButton(onClick = { show = !show }, modifier = Modifier.testTag("proxy_form_show")) {
                Text(stringResource(if (show) R.string.proxy_form_hide else R.string.proxy_form_show))
            }
            error?.let {
                Text(
                    stringResource(when (it) {
                        FormError.SERVER -> R.string.proxy_error_server
                        FormError.PORT -> R.string.proxy_error_port
                        FormError.CREDENTIALS -> R.string.proxy_error_credentials
                        FormError.SECRET -> R.string.proxy_error_secret
                        FormError.NAME -> R.string.proxy_error_name
                    }),
                    color = HeroDangerSoft, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("proxy_form_error"),
                )
            }
            addError?.let { Text(stringResource(it), color = HeroDangerSoft, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("proxy_form_error")) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroButton(onClick = ::save, solid = true, tone = HeroTone.Primary, modifier = Modifier.testTag("proxy_form_save")) { Text(stringResource(R.string.proxy_form_save)) }
                HeroButton(onClick = onClose, modifier = Modifier.testTag("proxy_form_cancel")) { Text(stringResource(R.string.proxy_form_cancel)) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxyField(value: String, onChange: (String) -> Unit, label: String, tag: String, type: KeyboardType = KeyboardType.Text, mask: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(HeroRadiusMd))
                .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.border else HeroDivider), RoundedCornerShape(HeroRadiusMd))
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                visualTransformation = if (mask) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (mask) KeyboardType.Password else type, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag(tag),
            )
        }
    }
}
