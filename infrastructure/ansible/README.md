# Calcifer Ansible Configuration

Ansible project for automating the configuration of the local server where the Calcifer application runs.

## Prerequisites

- Ansible >= 2.14 installed on the control machine
- SSH access configured to the target server
- SSH private key available at `~/.ssh/github_id`
- `/data` partition mounted on target server (Timeshift will create its structure automatically)

## Quick Start

### 1. Test connection

```bash
cd infrastructure/ansible
ansible-playbook playbooks/test-connection.yml --ask-become-pass
```

### 2. Configure server (install Timeshift)

```bash
ansible-playbook playbooks/site.yml --ask-become-pass
```

### 3. Install Docker Engine

```bash
ansible-playbook playbooks/install-docker.yml --ask-become-pass
```

After installation, logout and login again to run Docker without sudo.

## Snapshot Management

### Create a snapshot (before milestones)

```bash
ansible-playbook playbooks/snapshot-create.yml -e "snapshot_name='pre-docker-install'" --ask-become-pass
```

### List available snapshots

```bash
ansible-playbook playbooks/snapshot-list.yml --ask-become-pass
```

### Restore a snapshot (rollback)

```bash
ansible-playbook playbooks/snapshot-restore.yml -e "snapshot_name='pre-docker-install'" --ask-become-pass
```

Note: Restore requires typing 'YES' to confirm and manual reboot after completion.

## Directory Structure

```
ansible/
├── ansible.cfg                     # Ansible configuration
├── README.md
├── inventory/
│   ├── hosts.yml                   # Server inventory
│   └── group_vars/
│       └── all.yml                 # Centralized variables (DRY principle)
├── playbooks/
│   ├── site.yml                    # Main setup playbook (Timeshift)
│   ├── install-docker.yml          # Install Docker Engine
│   ├── snapshot-create.yml         # Create snapshot with mnemonic name
│   ├── snapshot-list.yml           # List snapshots with names
│   ├── snapshot-restore.yml        # Restore snapshot by name
│   └── test-connection.yml         # Test SSH connectivity
└── roles/
    ├── docker/
    │   ├── defaults/main.yml       # Docker default variables
    │   ├── handlers/main.yml       # Service handlers
    │   └── tasks/
    │       ├── main.yml            # Main installation tasks
    │       ├── setup-repository.yml    # Setup Docker apt repository
    │       └── configure-user.yml      # Add user to docker group
    └── timeshift/
        ├── defaults/main.yml       # Role-specific defaults
        ├── handlers/
        ├── tasks/
        │   ├── main.yml            # Installation and configuration
        │   ├── check-installed.yml # Reusable: verify Timeshift installed
        │   ├── validate-snapshot-name.yml  # Reusable: validate name param
        │   └── find-snapshot-by-name.yml   # Reusable: find snapshot by name
        └── templates/
            └── timeshift.json.j2   # Timeshift configuration template
```

## Target Server

- **Host:** 192.168.8.180
- **User:** dmgiangi
- **SSH Port:** 22

## Timeshift Configuration

All configuration is centralized in `inventory/group_vars/all.yml`:

- **Mode:** RSYNC (ext4 filesystem)
- **Backup partition:** `/data` (Timeshift creates `/data/timeshift/snapshots/` automatically)
- **Retention:** 3 snapshots
- **Excluded directories:** `/home`, `/data`, `/var/lib/docker`, `/tmp`, `/var/tmp`, `/var/cache`

## Docker Configuration

Docker Engine is installed from the official Docker repository following the
[official documentation](https://docs.docker.com/engine/install/ubuntu/).

Installed components:

- Docker Engine (`docker-ce`)
- Docker CLI (`docker-ce-cli`)
- containerd (`containerd.io`)
- Docker Buildx plugin
- Docker Compose plugin

The user specified in `docker_user` (default: `ansible_user`) is added to the
`docker` group to allow running Docker commands without sudo.

**Note:** After installation, logout and login again to apply group permissions.

## Architecture Notes

This Ansible project follows Clean Code and SOLID principles:

- **DRY:** Variables centralized in `group_vars/all.yml`, reusable tasks in role
- **Single Responsibility:** Each playbook has one purpose
- **Open/Closed:** Configuration via variables, no hardcoded values
- **Input Validation:** All playbooks validate required parameters

## Recommended Workflow

1. **Test connection** to verify SSH access
2. **Create a snapshot** before major changes (e.g., `pre-docker-install`)
3. **Install Docker** using the dedicated playbook
4. **Create another snapshot** after successful installation (e.g., `post-docker-install`)

