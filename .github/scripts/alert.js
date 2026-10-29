// One open issue per failing scheduled check (.github/workflows/probe.yml and pin-health.yml), assigned to the
// repository's owner, so a failure reaches Sam as an issue email whoever last edited the workflow (GitHub mails a
// failed scheduled run only to the person who last changed its cron line, and a line Claude wrote reaches no one).
//
// A check that fails with no open issue for it opens one; one that keeps failing has the issue's body brought up to
// date (no new notification, however long the outage); one that passes again closes the issue with a comment. The
// issue is found by its marker, so its title can be edited. The run is marked failed when the check failed.

const LABEL = 'alert';

const marker = key => `<!-- alert:${key} -->`;
const runs = body => {
  const found = /<!-- alert-runs:(\d+) -->/.exec(body);
  return found ? Number(found[1]) : 1;
};

function body({ key, since, now, count, detail, runUrl }) {
  return `${marker(key)}
<!-- alert-runs:${count} -->
**Failing since** ${since}, **last seen failing** ${now} (${count} run${count === 1 ? '' : 's'}, [latest](${runUrl})).

${detail}

This issue is kept by \`.github/scripts/alert.js\`: its body is brought up to date while the check keeps failing \
and it is closed with a comment when the check passes again.`;
}

// context.payload.repository.owner.login is the owner on every event; context.repo.owner is the same string.
module.exports = async function alert({ github, context, core }, { key, title, healthy, detail, now = new Date() }) {
  const repo = context.repo;
  const runUrl = `${context.serverUrl}/${repo.owner}/${repo.repo}/actions/runs/${context.runId}`;
  const when = now.toISOString().replace(/\.\d{3}Z$/, 'Z');
  const text = (detail && detail.trim()) || 'The check gave no detail; see the run.';

  const open = await github.paginate(github.rest.issues.listForRepo,
    { ...repo, state: 'open', labels: LABEL, per_page: 100 });
  const issue = open.find(candidate => !candidate.pull_request && (candidate.body || '').includes(marker(key)));

  if (!healthy) {
    core.setFailed(text);
    if (issue) {
      const count = runs(issue.body) + 1;
      const since = (/\*\*Failing since\*\* (\S+),/.exec(issue.body) || [])[1] || when;
      await github.rest.issues.update({ ...repo, issue_number: issue.number,
        body: body({ key, since, now: when, count, detail: text, runUrl }) });
      core.info(`#${issue.number}: still failing, ${count} runs`);
      return { action: 'updated', number: issue.number };
    }
    await github.rest.issues
      .createLabel({ ...repo, name: LABEL, color: 'b60205', description: 'A scheduled check is failing' })
      .catch(error => { if (error.status !== 422) throw error; }); // 422: it's there already
    const { data: made } = await github.rest.issues.create({ ...repo, title, labels: [LABEL], assignees: [repo.owner],
      body: body({ key, since: when, now: when, count: 1, detail: text, runUrl }) });
    core.info(`#${made.number}: opened`);
    return { action: 'opened', number: made.number };
  }

  if (issue) {
    await github.rest.issues.createComment({ ...repo, issue_number: issue.number,
      body: `Passing again at ${when} ([run](${runUrl})): ${text}` });
    await github.rest.issues.update({ ...repo, issue_number: issue.number, state: 'closed', state_reason: 'completed' });
    core.info(`#${issue.number}: closed, passing again`);
    return { action: 'closed', number: issue.number };
  }
  core.info('passing, nothing open');
  return { action: 'none' };
};

module.exports.LABEL = LABEL;
module.exports.marker = marker;
