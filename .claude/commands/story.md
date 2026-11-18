---
description: Implement a Tune2AI backlog story end to end
argument-hint: T2AI-0XX
---

Implement backlog story $ARGUMENTS.

1. Find the story with `grep -rn "### $ARGUMENTS " docs/backlog`. Read it, AGENTS.md, and the requirements and decisions it references.
2. Check every story in **Depends on** has `Status: Done`. If any doesn't, stop and report which ones.
3. Set the story's Status to `In progress`.
4. Work the unticked tasks in order. After each task: build and run the relevant tests, then tick the checkbox. Don't tick anything you haven't verified.
5. If a task turns out to be wrong, unnecessary, or missing, edit the story and explain the change in your summary.
6. When all tasks are ticked, check the Definition of Done in AGENTS.md, then set Status to `Done`.
7. Finish with: what changed, test results (with the commands you ran), and any follow-up for later stories. Don't commit unless asked.
