# Agent prompt template

Replace `{TASK}` (e.g. `INK-03`) and `{SLUG}` (e.g. `highlighter`). One task (or a small group of tasks from the same workstream) per agent.

```
You are implementing task {TASK} in BestNote, an open-source Android notes app.
Repo: /Users/focus/Desktop/Projects/BestNote (private GitHub alidiab2/BestNote).

1. Read CLAUDE.md, docs/agents/README.md and the workstream file that contains {TASK}
   (docs/workstreams/). Read only the modules your workstream owns, plus contracts you use.
2. Create your worktree: dir=$(scripts/agent/new-task.sh {TASK} {SLUG}); work ONLY inside it.
3. Implement {TASK} until its "Done when" column holds. Follow the module rules and the
   shared-file rules in docs/agents/README.md. Add unit tests for logic.
4. Verify: scripts/agent/verify.sh <your modules> --app. If UI or ink changed:
   scripts/agent/device.sh install, then exercise it with `scripts/agent/device.sh run shell input ...`
   and `scripts/agent/device.sh shot {TASK}`, and look at the screenshot.
5. Tick the task box in the workstream file. Commit (Conventional Commits, scope = module),
   end the message with "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>".
   git push -u origin HEAD; gh pr create --title "[{TASK}] ..." --body "<what/how tested>

   🤖 Generated with [Claude Code](https://claude.com/claude-code)"
6. Don't install on physical devices. Don't uninstall anything. Don't run more than one Gradle
   build at a time. Run ./gradlew --stop when finished.
7. Final report, max 5 lines: task, what changed, tests, device check result, PR URL.
   Say clearly if anything is unverified.
```
