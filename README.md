# gitlab-backup-with-jenkins-ansible

Ansible automation, scheduled by Jenkins, that:

1. Backs up every project on a GitLab instance via GitLab's own
   [Project Export API](https://docs.gitlab.com/ee/api/project_import_export.html)
   (repo + issues + MRs + wiki + CI config + settings, not just git data).
2. Uploads the backup to object storage (MinIO/S3-compatible), with a
   retention window (10-30 days, adjustable per run) that prunes anything
   older on every run.
3. Proves each backup is actually restorable by importing it into a
   **disposable** throwaway GitLab CE container (`docker-compose`) and
   checking the restored project really has its repository content - not
   just that the archive file exists. Optionally left running afterward
   (`KEEP_RESTORE_FOR_INSPECTION`) so you can browse the result yourself
   instead of only trusting the automated pass/fail.

## Layout

```
docker-compose.gitlab-source.yml   - throwaway source GitLab (dev/testing only -
                                      point gitlab_url at a real instance for real use)
docker-compose.gitlab-restore.yml  - disposable per-run restore-verification target,
                                      spun up by gitlab_restore_test, torn down unless
                                      KEEP_RESTORE_FOR_INSPECTION is set
docker-compose.minio.yml           - throwaway object storage (dev/testing only -
                                      point minio_endpoint at a real one for real use)
.env.example                       - template for docker-compose.minio.yml's bootstrap
                                      credentials (copy to .env, gitignored)
roles/gitlab_backup/                - lists projects, exports, downloads, writes manifest.json
roles/gitlab_backup_upload/         - uploads the run's backup to object storage, prunes old ones
roles/gitlab_restore_test/          - imports the manifest's backups into a throwaway
                                       GitLab, verifies, tears down (or not)
playbooks/backup.yml               - backup + upload, no restore-verify
playbooks/backup_and_verify.yml    - backup + upload + restore-verify (what Jenkins runs)
infra-GitlabBackup.groovy          - Jenkins pipeline (scheduled, "Pipeline script from SCM")
backups/<timestamp>/               - one directory per run: *.tar.gz exports + manifest.json
                                      (local staging before upload - object storage is the
                                      durable copy; local run directories older than the
                                      same retention window get pruned too, so disk usage
                                      stays bounded the same way the bucket does)
```

## Requirements

- `docker` + `docker-compose` (standalone binary) on whatever host runs
  this. **The OS user running the playbook must be in the `docker`
  group** (`sudo usermod -aG docker <user>`, then restart whatever process
  runs the playbook - for a Jenkins agent, that means restarting the agent
  itself, since group membership only applies to new sessions). Hit for
  real on a Jenkins agent whose user wasn't in that group -
  `roles/gitlab_restore_test` and `roles/gitlab_backup_upload` both check
  `docker info` up front and fail with this exact fix instead of a raw
  "permission denied" buried in a docker-compose/docker-run command's
  stderr.
- `curl` on the Ansible controller (used directly for the restore
  import - see "Design notes").
- **An admin Personal Access Token (`api` scope) for the source GitLab
  instance.** Not just any token: `roles/gitlab_backup` lists every
  project via `GET /api/v4/projects` with no membership filter, which
  GitLab only expands to "every project on the instance" for an
  administrator - a non-admin token only sees what it already has access
  to, silently backing up fewer projects (down to zero, hit for real
  against a genuine multi-project instance) with no error unless you
  check the count. The role checks the token's admin status up front and
  fails with a clear message if it isn't one.
- **An access key/secret key for a MinIO/S3-compatible object storage
  endpoint** (`minio_access_key`/`minio_secret_key`, or the Jenkins
  `minio-credentials` credential) - required even for `backup.yml` alone,
  since upload is on by default (`gitlab_backup_upload_enabled: true`; set
  to `false` to skip it for a quick local-only test with no MinIO
  running).

## Usage

```bash
# backup + upload to object storage, no restore-verify
ansible-playbook playbooks/backup.yml \
  -e gitlab_url=http://your-gitlab:port \
  -e gitlab_token=glpat-xxxxxxxxxxxxxxxxxxxx \
  -e minio_endpoint=http://your-minio:port \
  -e minio_access_key=... -e minio_secret_key=... \
  -e gitlab_backup_retention_days=30

# backup + upload + prove it's restorable (spins up + tears down a disposable GitLab CE)
ansible-playbook playbooks/backup_and_verify.yml \
  -e gitlab_url=http://your-gitlab:port \
  -e gitlab_token=glpat-xxxxxxxxxxxxxxxxxxxx \
  -e minio_endpoint=http://your-minio:port \
  -e minio_access_key=... -e minio_secret_key=... \
  -e gitlab_backup_retention_days=30

# same, but leave the restore instance running afterward for manual inspection
# (prints URL/username/password at the end - see "Manual inspection" below)
ansible-playbook playbooks/backup_and_verify.yml \
  -e gitlab_url=http://your-gitlab:port -e gitlab_token=glpat-xxxxxxxxxxxxxxxxxxxx \
  -e minio_access_key=... -e minio_secret_key=... \
  -e gitlab_restore_teardown=false
```

`gitlab_token`/`minio_access_key`/`minio_secret_key` can also come from env
vars (see `inventory/group_vars/all.yml`) - what the Jenkins pipeline uses
via `withCredentials` bindings, so nothing sensitive appears in a build
parameter or shell history. In Jenkins, `KEEP_RESTORE_FOR_INSPECTION` and
`BACKUP_RETENTION_DAYS` (choice, 10-30) are build parameters - no need to
edit `-e` flags by hand.

## Manual inspection after a run

`gitlab_restore_teardown: false` (Jenkins: check `KEEP_RESTORE_FOR_INSPECTION`)
leaves the disposable restore GitLab container running instead of tearing
it down - useful when the automated pass/fail isn't enough and you want to
actually browse the restored projects yourself. A random root password is
generated and printed at the end of the run (console log in Jenkins):

```
Restore instance kept running for manual inspection (gitlab_restore_teardown=false / KEEP_RESTORE_FOR_INSPECTION).
URL: http://<host>:8930
Username: root
Password: <random, printed here only - not stored anywhere>
Remember to tear it down manually when done: docker-compose -f docker-compose.gitlab-restore.yml down -v
```

This prints regardless of whether verification passed or failed (it's in
an `always:` block) - a failed run is exactly when you're most likely to
want to look at it yourself. Remember to actually tear it down when done
(the command is printed above) - it doesn't clean itself up.

## Development/testing source instance

This repo ships its own throwaway source GitLab
(`docker-compose.gitlab-source.yml`, `gitlab/gitlab-ce:17.10.5-ce.0`) for
developing/testing this automation without touching a real GitLab:

```bash
docker-compose -f docker-compose.gitlab-source.yml up -d
# wait ~5-8 min for first boot, then:
docker exec gitlab-source cat /etc/gitlab/initial_root_password   # root's auto-generated password
```

Data lives in named Docker volumes (`gitlab-source-config`/`-logs`/`-data`,
pinned via `name:` in the compose file), not host bind-mounts - stopping
and restarting the container (`docker-compose stop`/`up -d`, or a full
`docker-compose down` without `-v`) comes back up in seconds with
everything intact instead of re-running first-boot setup. Only `down -v`
or an explicit `docker volume rm` wipes it.

Point `gitlab_url`/`gitlab_token` at this instance (`http://localhost:8929`)
to test end-to-end without risk to a real GitLab. Swap in the real
self-hosted GitLab's URL + a real token for production use - nothing else
in this project assumes the throwaway instance.

## Development/testing object storage

This repo also ships its own throwaway MinIO (`docker-compose.minio.yml`)
for developing/testing the upload/retention step without a real object
storage endpoint:

```bash
cp .env.example .env   # then edit MINIO_ROOT_PASSWORD to a real secret
docker-compose -f docker-compose.minio.yml up -d
```

Data lives in a named volume (`gitlab-backup-minio-data`) the same way
`gitlab-source`'s does - stop/restart keeps it. Point
`minio_endpoint`/`minio_access_key`/`minio_secret_key` at this instance
(`http://localhost:9010`, credentials from `.env`) to test end-to-end.
Swap in a real MinIO/S3-compatible endpoint for production use.

## Verification status

`playbooks/backup_and_verify.yml` run for real end-to-end against the
throwaway source instance (10 test projects, each with a real commit),
with object storage upload and manual-inspection both enabled: backup
exported/downloaded/uploaded all 10 to MinIO, the restore role imported
all 10 into a genuinely fresh disposable GitLab CE, and verification
confirmed real repository content (an actual branch + commit, not just
`import_status: finished`) for all 10 -
`RESTORE VERIFICATION PASSED - all 10 project(s) restored correctly.`,
`failed=0` in the play recap. Also confirmed for real: the uploaded
objects actually exist in the MinIO bucket; the generated root password
is genuinely valid (`User#valid_password?` checked directly, not just
"the task didn't error"); retention pruning both removes objects/local
directories older than the window and leaves recent ones untouched
(tested both directions, not just the delete path); and with
`gitlab_restore_teardown: false` the restore instance is correctly left
running with connection info printed instead of torn down. Eight real
bugs were found and fixed getting the whole pipeline working (six before
this feature set - see below - plus two more building object storage
upload: `mc cp --recursive`'s trailing-slash nesting quirk, and the
`docker info` access check needing to cover this role too, not just
restore-verify).

## Design notes

**`mc cp --recursive` needs a trailing slash on the source, not just the
destination**: uploading `gitlab_backup_run_dir` (no trailing slash)
landed every file one directory level too deep -
`gitlab-backups/<run>/upload/1__demo-project-01.tar.gz` instead of
`gitlab-backups/<run>/1__demo-project-01.tar.gz` - because `mc cp -r SRC
DST` treats a source directory like `cp -r dir dest/` (copies the
directory itself, named after its own mount point) unless SRC also ends
in `/`, which instead copies its *contents* directly into DST (like `cp
-r dir/. dest/`). Confirmed directly by uploading the same real backup run
both ways and listing the resulting bucket structure with `mc ls`.

**Upload happens before restore-verification, not after**: a transient
disposable-restore-container hiccup should never risk a legitimate backup
existing only on local disk. Verification is a quality signal layered on
top of an already-safely-offsite backup, not a gate for whether to keep
it - so `playbooks/backup_and_verify.yml` runs `gitlab_backup_upload`
right after `gitlab_backup`, before `gitlab_restore_test`.

**Retention prunes local `backups/` the same way it prunes the bucket**:
object storage is the durable copy, but without also pruning
`backups/<timestamp>/` locally, disk usage on whatever host runs this
would grow unbounded across scheduled runs even though the bucket stays
bounded. Uses `ansible.builtin.find`'s `age` filter (`recurse: false`, so
it only considers the immediate `backups/<timestamp>/` directories, never
descends into what's inside them) with the same
`gitlab_backup_retention_days` window MinIO's own `mc rm --older-than`
uses - tested both directions directly (a deliberately-backdated fake old
run directory got removed; a recent one and the current run's own
directory did not).

**Manual-inspection connection info lives in the `always:` block, after
teardown became conditional**: printing it *after* the final
pass/fail assert would mean an assert failure (exactly when someone is
most likely to want to look at the instance themselves) skips straight
past it and never prints anything. Moved into `always:`, ahead of the now-
conditional teardown task, so it prints regardless of whether verification
passed or failed. The random root password
(`User#password =`/`save!(validate: false)`) is generated fresh per run
and only ever appears in that run's console output - confirmed genuinely
valid directly via `User#valid_password?`, not just "the task didn't
error."

**Project listing requires an admin token - `membership=true` silently
under-lists**: the first real production run (against a genuine
multi-project self-hosted instance, via Jenkins) reported "Found 0
project(s)" and failed cleanly, even though the instance had real
projects and the token/URL were both correct. Root cause: the original
`GET /api/v4/projects?membership=true&...` only lists projects the
*token's own user* is a member of - on the throwaway dev/test instance
this went unnoticed because `root` had created (and was therefore a
member/owner of) every test project itself, but on a real instance an
admin token's user is very likely not a member of most projects. Fixed by
dropping `membership=true` entirely: GitLab's API special-cases admin
tokens on `GET /api/v4/projects` (no membership filter) to return every
project on the instance. The role now also checks `GET /api/v4/user` up
front and fails with a clear "token is not an admin token" message
instead of a confusing "0 projects found" if the token lacks admin
rights - reproduced directly with a real non-admin token/user created for
the purpose, confirmed against the throwaway source instance.

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
