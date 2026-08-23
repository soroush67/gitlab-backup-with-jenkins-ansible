// Jenkins Pipeline job: "Pipeline script from SCM" pointing at this repo,
// with Script Path = infra-GitlabBackup.groovy
//
// Runs ansible-playbook (playbooks/backup_and_verify.yml) on a schedule:
// exports every project from the source GitLab instance, downloads the
// archives, uploads them to object storage (RustFS by default, or any
// other S3-compatible endpoint, with adjustable retention), then proves
// each backup is actually restorable by importing it into a disposable
// throwaway GitLab CE container (docker-compose) and checking the
// restored project really has its repository content. See README.md for
// the full design.
//
// One-time Jenkins setup required before this works:
//   1. Set AGENT_NODE_LABEL below to the real label of the agent node that
//      has docker + docker-compose + ansible-core installed, has its OS
//      user in the `docker` group (checked up front by the playbook - a
//      clear failure otherwise, not a silent hang), and can reach the
//      source GitLab instance (this repo's own
//      docker-compose.gitlab-source.yml, or a real self-hosted GitLab once
//      you point GITLAB_URL at it).
//   2. Create a "Secret text" credential holding an ADMIN GitLab Personal
//      Access Token (api scope) for the source instance, with ID
//      GITLAB_TOKEN_CREDENTIALS_ID below - must be an admin token, since
//      backing up every project on the instance requires one (see README).
//   3. Create a "Username with password" credential holding the object
//      storage access key (as username) / secret key (as password), with
//      ID OBJECT_STORAGE_CREDENTIALS_ID below - must match whatever
//      RustFS/S3-compatible endpoint OBJECT_STORAGE_ENDPOINT points at.
//   4. Adjust GITLAB_URL/OBJECT_STORAGE_ENDPOINT below (or override via
//      the job's own build parameters) if they aren't this repo's default
//      throwaway containers on localhost.
//   5. This job must not run concurrently with itself (disableConcurrentBuilds
//      below) - the restore step uses a single fixed container
//      name/port (gitlab-restore:8930), so two runs would collide.

def AGENT_NODE_LABEL = 'CHANGE_ME_GITLAB_BACKUP_AGENT_LABEL'
def GITLAB_TOKEN_CREDENTIALS_ID = 'gitlab-backup-token'
def OBJECT_STORAGE_CREDENTIALS_ID = 'object-storage-credentials'

pipeline {
    agent { label AGENT_NODE_LABEL }

    options {
        disableConcurrentBuilds()
        timestamps()
    }

    triggers {
        // Nightly at 02:00. Adjust to taste - this is a schedule, not a
        // hardcoded requirement.
        cron('0 2 * * *')
    }

    parameters {
        string(name: 'GITLAB_URL', defaultValue: 'http://localhost:8929', description: 'Source GitLab instance to back up')
        string(name: 'OBJECT_STORAGE_ENDPOINT', defaultValue: 'http://localhost:9010', description: 'Object storage (RustFS/S3-compatible) endpoint backups are uploaded to')
        choice(name: 'BACKUP_RETENTION_DAYS', choices: ['30', '25', '20', '15', '10'], description: 'Delete backups already in object storage older than this many days, on every run')
        booleanParam(name: 'VERIFY_RESTORE', defaultValue: true, description: 'After backing up, prove each backup is restorable via a disposable GitLab CE container (slower - a few minutes per run for the throwaway instance to boot)')
        booleanParam(name: 'KEEP_RESTORE_FOR_INSPECTION', defaultValue: false, description: 'Leave the disposable restore GitLab container running after this pipeline finishes, instead of tearing it down, so you can browse it yourself and see the result - only applies when VERIFY_RESTORE is checked. A random root password is generated and printed in this console log. Remember to tear it down manually when done (see the printed instructions).')
    }

    stages {
        stage('Checkout automation repo') {
            steps {
                git branch: 'main', url: 'https://github.com/soroush67/gitlab-backup-with-jenkins-ansible.git'
            }
        }

        stage('Backup GitLab projects') {
            when { expression { !params.VERIFY_RESTORE } }
            environment {
                GITLAB_URL = "${params.GITLAB_URL}"
                OBJECT_STORAGE_ENDPOINT = "${params.OBJECT_STORAGE_ENDPOINT}"
                BACKUP_RETENTION_DAYS = "${params.BACKUP_RETENTION_DAYS}"
            }
            steps {
                withCredentials([
                    string(credentialsId: GITLAB_TOKEN_CREDENTIALS_ID, variable: 'GITLAB_TOKEN'),
                    usernamePassword(credentialsId: OBJECT_STORAGE_CREDENTIALS_ID, usernameVariable: 'OBJECT_STORAGE_ACCESS_KEY', passwordVariable: 'OBJECT_STORAGE_SECRET_KEY'),
                ]) {
                    sh '''
                        set -e
                        ansible-playbook playbooks/backup.yml \
                          -e gitlab_url="$GITLAB_URL" \
                          -e gitlab_token="$GITLAB_TOKEN" \
                          -e object_storage_endpoint="$OBJECT_STORAGE_ENDPOINT" \
                          -e object_storage_access_key="$OBJECT_STORAGE_ACCESS_KEY" \
                          -e object_storage_secret_key="$OBJECT_STORAGE_SECRET_KEY" \
                          -e gitlab_backup_retention_days="$BACKUP_RETENTION_DAYS"
                    '''
                }
            }
        }

        stage('Backup + verify restore') {
            when { expression { params.VERIFY_RESTORE } }
            environment {
                GITLAB_URL = "${params.GITLAB_URL}"
                OBJECT_STORAGE_ENDPOINT = "${params.OBJECT_STORAGE_ENDPOINT}"
                BACKUP_RETENTION_DAYS = "${params.BACKUP_RETENTION_DAYS}"
                KEEP_RESTORE_FOR_INSPECTION = "${params.KEEP_RESTORE_FOR_INSPECTION}"
            }
            steps {
                withCredentials([
                    string(credentialsId: GITLAB_TOKEN_CREDENTIALS_ID, variable: 'GITLAB_TOKEN'),
                    usernamePassword(credentialsId: OBJECT_STORAGE_CREDENTIALS_ID, usernameVariable: 'OBJECT_STORAGE_ACCESS_KEY', passwordVariable: 'OBJECT_STORAGE_SECRET_KEY'),
                ]) {
                    sh '''
                        set -e
                        ansible-playbook playbooks/backup_and_verify.yml \
                          -e gitlab_url="$GITLAB_URL" \
                          -e gitlab_token="$GITLAB_TOKEN" \
                          -e object_storage_endpoint="$OBJECT_STORAGE_ENDPOINT" \
                          -e object_storage_access_key="$OBJECT_STORAGE_ACCESS_KEY" \
                          -e object_storage_secret_key="$OBJECT_STORAGE_SECRET_KEY" \
                          -e gitlab_backup_retention_days="$BACKUP_RETENTION_DAYS" \
                          -e gitlab_restore_teardown="$([ "$KEEP_RESTORE_FOR_INSPECTION" = "true" ] && echo false || echo true)"
                    '''
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'backups/*/manifest.json', allowEmptyArchive: true
        }
    }
}
