package com.mux.android.code

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Change(val path: String, val code: Char, val staged: Boolean)
class RepoStatus(val root: File, val branch: String, val changes: List<Change>)
class LogLine(val id: String, val msg: String, val author: String, val time: String)

/** Git for Android, built on JGit (pure Java). HTTPS only. There is no git binary on the phone. */
object GitEngine {
    fun init() {
        try {
            System.setProperty("user.home", Env.home)
            org.eclipse.jgit.util.FS.DETECTED.setUserHome(File(Env.home))
        } catch (e: Throwable) {
        }
    }

    fun tokenize(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = '\u0000'
        var has = false
        for (c in line) {
            when {
                q != '\u0000' -> if (c == q) q = '\u0000' else sb.append(c)
                c == '"' || c == '\'' -> { q = c; has = true }
                c.isWhitespace() -> if (sb.isNotEmpty() || has) { out.add(sb.toString()); sb.setLength(0); has = false }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty() || has) out.add(sb.toString())
        return out
    }

    private fun creds(): CredentialsProvider? =
        if (Prefs.gitToken.isBlank()) null
        else UsernamePasswordCredentialsProvider(Prefs.gitUser.ifBlank { "token" }, Prefs.gitToken)

    fun open(dir: File): Git? {
        return try {
            val b = FileRepositoryBuilder().findGitDir(dir)
            if (b.gitDir == null) null else Git(b.readEnvironment().build())
        } catch (e: Exception) {
            null
        }
    }

    fun isRepo(dir: File): Boolean = open(dir)?.let { it.close(); true } ?: false

    fun status(dir: File): RepoStatus? {
        val g = open(dir) ?: return null
        try {
            val st = g.status().call()
            val out = ArrayList<Change>()
            st.added.forEach { out.add(Change(it, 'A', true)) }
            st.changed.forEach { out.add(Change(it, 'M', true)) }
            st.removed.forEach { out.add(Change(it, 'D', true)) }
            st.modified.forEach { out.add(Change(it, 'M', false)) }
            st.missing.forEach { out.add(Change(it, 'D', false)) }
            st.untracked.forEach { out.add(Change(it, 'U', false)) }
            st.conflicting.forEach { out.add(Change(it, 'C', false)) }
            return RepoStatus(g.repository.workTree, g.repository.branch ?: "HEAD", out)
        } finally {
            g.close()
        }
    }

    fun log(dir: File, n: Int = 25): List<LogLine> {
        val g = open(dir) ?: return emptyList()
        try {
            val f = SimpleDateFormat("MMM d HH:mm", Locale.US)
            val l = ArrayList<LogLine>()
            val commits = try { g.log().setMaxCount(n).call() } catch (e: Exception) { return emptyList() }
            for (c: RevCommit in commits) {
                l.add(LogLine(c.name.take(7), c.shortMessage, c.authorIdent.name, f.format(Date(c.commitTime * 1000L))))
            }
            return l
        } finally {
            g.close()
        }
    }

    fun branches(dir: File): Pair<String, List<String>> {
        val g = open(dir) ?: return "" to emptyList()
        try {
            val cur = g.repository.branch ?: ""
            val l = g.branchList().call().map { it.name.removePrefix("refs/heads/") }
            return cur to l
        } finally {
            g.close()
        }
    }

    fun diff(dir: File, path: String, staged: Boolean): String {
        val g = open(dir) ?: return "not a git repository"
        try {
            val out = ByteArrayOutputStream()
            val fmt = DiffFormatter(out)
            fmt.setRepository(g.repository)
            val cmd = g.diff().setCached(staged)
            if (path.isNotEmpty()) cmd.setPathFilter(PathFilter.create(path))
            val entries = cmd.call()
            fmt.format(entries)
            fmt.flush()
            val s = out.toString("UTF-8")
            return if (s.isBlank()) (if (staged) "(no staged differences)" else "(no differences)") else s
        } finally {
            g.close()
        }
    }

    private fun need(dir: File): Git = open(dir) ?: throw IllegalStateException("not a git repository (use: git init)")

    fun stage(dir: File, c: Change) {
        val g = need(dir)
        try {
            if (c.code == 'D') g.rm().addFilepattern(c.path).call() else g.add().addFilepattern(c.path).call()
        } finally { g.close() }
    }

    fun stageAll(dir: File) {
        val g = need(dir)
        try {
            g.add().addFilepattern(".").call()
            g.add().addFilepattern(".").setUpdate(true).call()
        } finally { g.close() }
    }

    fun unstage(dir: File, path: String) {
        val g = need(dir)
        try { g.reset().addPath(path).call() } finally { g.close() }
    }

    fun discard(dir: File, c: Change) {
        val g = need(dir)
        try {
            if (c.code == 'U') File(g.repository.workTree, c.path).delete()
            else g.checkout().addPath(c.path).call()
        } finally { g.close() }
    }

    fun commit(dir: File, msg: String): String {
        val g = need(dir)
        try {
            val name = Prefs.gitName.ifBlank { "MUX Code" }
            val email = Prefs.gitEmail.ifBlank { "code@mux.local" }
            val c = g.commit().setMessage(msg).setAuthor(name, email).setCommitter(name, email).call()
            return "[${g.repository.branch} ${c.name.take(7)}] ${c.shortMessage}"
        } finally { g.close() }
    }

    fun checkout(dir: File, name: String, create: Boolean) {
        val g = need(dir)
        try { g.checkout().setName(name).setCreateBranch(create).call() } finally { g.close() }
    }

    fun pull(dir: File): String {
        val g = need(dir)
        try {
            val r = g.pull().setCredentialsProvider(creds()).call()
            return if (r.isSuccessful) "pull ok: ${r.mergeResult?.mergeStatus ?: "up to date"}" else "pull finished with problems: ${r.mergeResult?.mergeStatus}"
        } finally { g.close() }
    }

    fun fetch(dir: File): String {
        val g = need(dir)
        try {
            val r = g.fetch().setCredentialsProvider(creds()).call()
            return "fetch ok" + (r.messages.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        } finally { g.close() }
    }

    fun push(dir: File): String {
        val g = need(dir)
        try {
            val res = g.push().setCredentialsProvider(creds()).call()
            val sb = StringBuilder()
            for (r in res) for (u in r.remoteUpdates) sb.append("${u.remoteName}: ${u.status}\n")
            if (sb.isEmpty()) sb.append("nothing to push")
            return sb.toString().trim()
        } finally { g.close() }
    }

    fun clone(url: String, target: File): String {
        val sw = StringWriter()
        val g = Git.cloneRepository().setURI(url).setDirectory(target)
            .setCredentialsProvider(creds())
            .setProgressMonitor(TextProgressMonitor(PrintWriter(sw)))
            .call()
        g.close()
        return "cloned into ${target.absolutePath}"
    }

    fun init(dir: File): String {
        Git.init().setDirectory(dir).call().close()
        return "initialized empty repository in ${dir.absolutePath}/.git"
    }

    /** Subset of the git command line, used by the terminal and by the AI agent. */
    fun runCli(dir: File, args: List<String>): String {
        if (args.isEmpty()) return "usage: git <command>. Supported: init clone status add commit log branch checkout pull push fetch diff remote reset config"
        val cmd = args[0]
        val rest = args.drop(1)
        return try {
            when (cmd) {
                "init" -> init(dir)
                "clone" -> {
                    val url = rest.firstOrNull { !it.startsWith("-") } ?: return "usage: git clone <https-url> [folder]"
                    val name = rest.filter { !it.startsWith("-") }.getOrNull(1)
                        ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
                    clone(url, File(dir, name))
                }
                "status" -> {
                    val s = status(dir) ?: return "fatal: not a git repository (use: git init)"
                    val sb = StringBuilder("On branch ${s.branch}\n")
                    if (s.changes.isEmpty()) sb.append("nothing to commit, working tree clean")
                    for (c in s.changes) sb.append(if (c.staged) "staged   " else "unstaged ").append(c.code).append(' ').append(c.path).append('\n')
                    sb.toString()
                }
                "add" -> {
                    val g = need(dir)
                    try {
                        val paths = rest.filter { !it.startsWith("-") }
                        if (paths.isEmpty() || paths.contains(".") || rest.contains("-A") || rest.contains("--all")) {
                            g.close(); stageAll(dir)
                        } else {
                            for (p in paths) { g.add().addFilepattern(p).call(); g.add().addFilepattern(p).setUpdate(true).call() }
                        }
                    } finally { g.close() }
                    "added"
                }
                "commit" -> {
                    val i = rest.indexOf("-m")
                    val msg = if (i >= 0) rest.getOrNull(i + 1) else rest.firstOrNull { it.startsWith("--message=") }?.removePrefix("--message=")
                    if (msg.isNullOrBlank()) return "usage: git commit -m \"message\" [-a]"
                    if (rest.contains("-a") || rest.contains("-am")) stageAll(dir)
                    commit(dir, msg)
                }
                "log" -> {
                    val n = rest.indexOf("-n").let { if (it >= 0) rest.getOrNull(it + 1)?.toIntOrNull() else null } ?: 15
                    val l = log(dir, n)
                    if (l.isEmpty()) "no commits" else l.joinToString("\n") { "${it.id} ${it.msg} (${it.author}, ${it.time})" }
                }
                "branch" -> {
                    val name = rest.firstOrNull { !it.startsWith("-") }
                    if (name == null) {
                        val (cur, l) = branches(dir)
                        l.joinToString("\n") { (if (it == cur) "* " else "  ") + it }
                    } else {
                        val g = need(dir)
                        try { g.branchCreate().setName(name).call() } finally { g.close() }
                        "branch $name created"
                    }
                }
                "checkout", "switch" -> {
                    val create = rest.contains("-b") || rest.contains("-c")
                    val name = rest.firstOrNull { !it.startsWith("-") } ?: return "usage: git checkout [-b] <branch>"
                    checkout(dir, name, create)
                    "switched to $name"
                }
                "pull" -> pull(dir)
                "fetch" -> fetch(dir)
                "push" -> push(dir)
                "diff" -> {
                    val staged = rest.contains("--staged") || rest.contains("--cached")
                    diff(dir, rest.firstOrNull { !it.startsWith("-") } ?: "", staged)
                }
                "reset" -> {
                    val p = rest.firstOrNull { !it.startsWith("-") }
                    if (p == null) { stageAllReset(dir); "unstaged everything" } else { unstage(dir, p); "unstaged $p" }
                }
                "remote" -> {
                    val g = need(dir)
                    try {
                        if (rest.firstOrNull() == "add" && rest.size >= 3) {
                            g.remoteAdd().setName(rest[1]).setUri(URIish(rest[2])).call()
                            "remote ${rest[1]} added"
                        } else {
                            val l = g.remoteList().call()
                            if (l.isEmpty()) "no remotes" else l.joinToString("\n") { r -> "${r.name}\t${r.urIs.joinToString(",")}" }
                        }
                    } finally { g.close() }
                }
                "config" -> {
                    if (rest.size >= 2) {
                        when (rest[0]) {
                            "user.name" -> Prefs.gitName = rest[1]
                            "user.email" -> Prefs.gitEmail = rest[1]
                            else -> return "only user.name and user.email are supported"
                        }
                        Prefs.commit()
                        "saved ${rest[0]}"
                    } else "name: ${Prefs.gitName}\nemail: ${Prefs.gitEmail}"
                }
                else -> "git: '$cmd' is not supported in this app. Supported: init clone status add commit log branch checkout pull push fetch diff remote reset config"
            }
        } catch (e: Throwable) {
            "fatal: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun stageAllReset(dir: File) {
        val g = need(dir)
        try { g.reset().call() } finally { g.close() }
    }
}
