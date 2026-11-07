# Code

VS Code style code editor for Android 8.0+ in the Jarvis HUD theme (same family as Dialer, Scanner, Keyboard, Launcher, Messages, Weather, Terminal).

- Package: `com.mux.android.code` - Version: `1.0.0`

## Tabs
| Tab | What it does |
|---|---|
| FILES | Folder tree, new / rename / delete, search in all files, open any folder (app workspace or /sdcard) |
| EDIT | **Monaco** (the editor engine of VS Code): syntax colours for ~80 languages, IntelliSense for JS / TS / JSON / CSS / HTML, find and replace, command palette, multi-file tabs, auto save, symbol key row, optional terminal panel under the editor |
| TERM | Integrated terminal (real `sh`, tabs, history, TAB completion, colours). `git` is built in, no git binary needed |
| GIT | Init, clone, status, stage / unstage, discard, diff, commit, branches, pull, push, fetch, history (JGit) |
| AI | AI coding agent (Anthropic Claude, your own API key): reads, searches, edits files, runs commands and git |
| MORE | EXT (extensions), SET (settings), SYS (about, update) |

## Extensions (honest)
- Install `.vsix` files or search the free **Open VSX** registry (open-vsx.org) inside the app.
- Colour themes and snippets of extensions work (pick the theme in SET > EDITOR THEME).
- Extension **code** (language servers, debuggers, commands; it needs VS Code's Node.js extension host) does not run on Android. Such extensions install but only their themes / snippets are used.
- The Microsoft Marketplace cannot be used by third party apps. Open VSX has most popular themes and snippet packs.

## AI agent
- Settings > AI AGENT: add your Anthropic API key (console.anthropic.com), choose the model.
- Tools: list / read / search / write / replace in files, run shell commands, git.
- PERMISSIONS = ASK (default): every write, command and git change needs your ALLOW. AUTO skips asking.
- In the editor, select code and press ASK AI to send it to the chat.
- The key stays on your phone, calls are billed to your Anthropic account.

## Git
- HTTPS only. For private repos and push set username and a personal access token in SET > GIT. SSH is not supported.
- The same commands work in the terminal: `git status`, `git add .`, `git commit -m "msg"`, `git push`, `git clone <url>`, `git log`, `git diff`, `git branch`, `git checkout -b x`.

## Limits
- No PTY in the terminal: vi, nano, htop cannot run. No package manager; Android blocks running downloaded binaries. Node, Python, gcc are not available, so you can edit such projects but not run them (unless the phone has them).
- Files bigger than 3 MB or binary files are not opened.
- Monaco is a desktop editor: on a phone touch selection is basic, use the symbol row, PALETTE and the toolbar.

## Build
The editor engine (Monaco, MIT licence) is downloaded at build time:
- GitHub Action: automatic (step "Fetch Monaco editor").
- Local: `bash scripts/fetch-monaco.sh` once, then `gradle assembleDebug`.
If Monaco is missing the app falls back to a very simple text box.

`app/debug.keystore` is shared with the other apps. Updates are read from GitHub releases of `mahfuz0712/Code` (`AppInfo.kt`).
