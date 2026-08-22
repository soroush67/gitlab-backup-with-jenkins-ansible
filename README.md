# gitlab-backup-with-jenkins-ansible

Ansible automation, scheduled by Jenkins, that:

1. Backs up every project on a GitLab instance via GitLab's own
   [Project Export API](https://docs.gitlab.com/ee/api/project_import_export.html)
   (repo + issues + MRs + wiki + CI config + settings, not just git data).
2. Proves each backup is actually restorable by importing it into a
   **disposable** throwaway GitLab CE container (`docker-compose`) and
   checking the restored project really has its repository content - not
   just that the archive file exists.

## Layout

```
docker-compose.gitlab-source.yml   - throwaway source GitLab (dev/testing only -
                                      point gitlab_url at a real instance for real use)
docker-compose.gitlab-restore.yml  - disposable per-run restore-verification target,
                                      spun up and torn down by the gitlab_restore_test role
roles/gitlab_backup/                - lists projects, exports, downloads, writes manifest.json
roles/gitlab_restore_test/          - imports the manifest's backups into a throwaway
                                       GitLab, verifies, tears down
playbooks/backup.yml               - backup only
playbooks/backup_and_verify.yml    - backup + restore-verify (what Jenkins runs)
infra-GitlabBackup.groovy          - Jenkins pipeline (scheduled, "Pipeline script from SCM")
backups/<timestamp>/               - one directory per run: *.tar.gz exports + manifest.json
```

## Requirements

- `ansible-core` with the `uri`/`get_url` modules' multipart (`body_format:
  form-multipart`) support (ansible-core >= 2.10).
- `docker` + `docker-compose` (standalone binary) on whatever host runs
  this - only needed for `gitlab_restore_test`'s disposable container, not
  for `backup.yml` alone.
- A GitLab Personal Access Token with `api` scope for the source instance.

## Usage

```bash
# backup only
ansible-playbook playbooks/backup.yml \
  -e gitlab_url=http://your-gitlab:port \
  -e gitlab_token=glpat-xxxxxxxxxxxxxxxxxxxx

# backup + prove it's restorable (spins up + tears down a disposable GitLab CE)
ansible-playbook playbooks/backup_and_verify.yml \
  -e gitlab_url=http://your-gitlab:port \
  -e gitlab_token=glpat-xxxxxxxxxxxxxxxxxxxx
```

`gitlab_token` can also come from the `GITLAB_TOKEN` env var (see
`inventory/group_vars/all.yml`) - what the Jenkins pipeline uses via a
`withCredentials` binding, so the token never appears in a build parameter
or shell history.

## Development/testing source instance

This repo ships its own throwaway source GitLab
(`docker-compose.gitlab-source.yml`, `gitlab/gitlab-ce:17.10.5-ce.0`) for
developing/testing this automation without touching a real GitLab:

```bash
docker-compose -f docker-compose.gitlab-source.yml up -d
# wait ~5-8 min for first boot, then:
sudo cat gitlab-source-config/initial_root_password   # root's auto-generated password
```

Point `gitlab_url`/`gitlab_token` at this instance (`http://localhost:8929`)
to test end-to-end without risk to a real GitLab. Swap in the real
self-hosted GitLab's URL + a real token for production use - nothing else
in this project assumes the throwaway instance.

## Verification status

`playbooks/backup_and_verify.yml` run for real end-to-end against the
throwaway source instance (10 test projects, each with a real commit):
backup exported and downloaded all 10, the restore role imported all 10
into a genuinely fresh disposable GitLab CE, and verification confirmed
real repository content (an actual branch + commit, not just
`import_status: finished`) for all 10 -
`RESTORE VERIFICATION PASSED - all 10 project(s) restored correctly.`,
`failed=0` in the play recap, disposable container torn down cleanly
afterward. Five real bugs were found and fixed getting there - see
"Design notes" below for what they were and why each fix works.

## Design notes

**Why GitLab's Project Export API, not `git clone --mirror`**: a mirror
clone only captures git refs - it misses issues, merge requests, wiki,
CI/CD variables, and project settings, none of which show up in a restore
drill until you actually try to bring a project back. The export API
produces a single self-contained archive that's the same format GitLab's
own Import feature consumes, so "restore" here is a real GitLab
import, not a bespoke unpack script - the closest thing to proving a real
disaster-recovery restore would actually work.

**Why a disposable GitLab CE container for verification, not a persistent
one**: importing 10 projects into the same instance repeatedly would
accumulate stale state across runs (namespace collisions, previous runs'
imports polluting the pass/fail signal). `docker-compose down -v` after
every run guarantees each verification starts from a genuinely empty
GitLab, matching what a real disaster-recovery restore looks like (restore
onto a *new* instance, not reuse of an existing one).

**Rate limiting**: GitLab throttles both the project-export *and*
project-import endpoints fairly aggressively - hit real `429 Too Many
Requests` on each (export: after ~6 rapid calls; import: on projects 7-10
after the first 6 succeeded in rapid succession) while testing this
against the 10-project throwaway instance. Both `roles/gitlab_backup`'s
"Start an export for every project" and `roles/gitlab_restore_test`'s
"Import each backed-up project" retry on 429 with backoff rather than
treating it as a hard failure.

**Readiness checks poll `docker inspect`, not an HTTP endpoint from the
host**: GitLab's health-check endpoints are IP-allowlisted
(`gitlab_rails['monitoring_whitelist']`, default `127.0.0.0/8`). A curl
against the published port from the host arrives at nginx post-Docker-NAT,
not as `127.0.0.1` - confirmed directly (curl from the host got a real
`404` while `docker exec ... curl` and the compose file's own healthcheck,
both running inside the container's network namespace, got `200` at the
same moment). Polling `docker inspect --format '{{.State.Health.Status}}'`
sidesteps the whole problem.

**`import_sources` is empty by default on a fresh GitLab instance**: every
`POST /api/v4/projects/import` call returned a bare, detail-free `403
Forbidden` (Grape's generic `authorize!` rejection, not a validation error
or a rate limit) until `ApplicationSetting.current.import_sources` had
`gitlab_project` added to it. `roles/gitlab_restore_test` enables it via
`gitlab-rails runner` before importing - scoped to the disposable restore
instance only, never touches the real source GitLab.

**Project import uses `curl` via `command`, not `ansible.builtin.uri`**:
confirmed directly, with a same-file side-by-side comparison, that the
`uri` module's `body_format: form-multipart` corrupts binary `.tar.gz`
uploads in transit - `curl --form` succeeded every time on the identical
file/request; the `uri` module failed identically every time, with
GitLab's internal unpack reporting `gzip: stdin: not in gzip format`.
Shelling out to `curl` matches the proven-working path exactly.

**Token creation is idempotent**: the restore-instance API token uses a
fixed value so downstream tasks can reference it without threading a
runtime-generated secret through the whole role. Re-running against an
already-provisioned instance (e.g. with `gitlab_restore_teardown: false`
for debugging) would otherwise hit a real `PG::UniqueViolation` on
`index_personal_access_tokens_on_token_digest` - hit directly, from
reusing a container across manual testing. Fixed by deleting any existing
token with the same name before creating a fresh one.

**`gitlab_backup_run_dir` uses `set_fact`, not just a role default**: in
`backup_and_verify.yml`, `roles/gitlab_backup` and `roles/gitlab_restore_test`
both run in the same play. If `gitlab_backup_run_dir` were only a role
default (as it initially was), `gitlab_restore_test`'s own empty-string
default for the same variable name would silently win once that role
loads, since same-precedence role defaults resolve in role-inclusion order.
Fixed by pinning it with `set_fact` (a higher-precedence, genuinely
host-scoped fact) as the first real task in `gitlab_backup`.

**Verification checks real repository content, not just import status**:
after each import, `gitlab_restore_test` polls `import_status == 'finished'`
*and* calls `GET .../repository/branches` on the restored project -
`import_status` alone can be `finished` without proving the git data
actually landed; requiring at least one real branch closes that gap.

**Combined pass/fail assertion**: every project's verification result is
collected (`ignore_errors: true` + `register`, matching the pattern
already established in the kubespray-webui project's
`verify-cluster.yml`) so one project failing doesn't hide whether the
other nine passed - the final task asserts a single
`"RESTORE VERIFICATION PASSED/FAILED"` result summarizing all of them,
grep-able from a Jenkins console log.
