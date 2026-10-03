# Tenant-specific SOAR secret grants

`socp.soar.secrets.tenant-grants` is a JSON object supplied through
`SOAR_SECRET_TENANT_GRANTS`. The default is `{}`. An empty map grants no tenant
access. The Helm product overlay explicitly ships that empty fail-closed value.
The core Compose deployment does not run SOAR; separately launched SOAR processes
use the same environment variable.

Each entry maps one exact tenant ID to an array of exact secret references.
References may use `env://`, `secret://`, `k8s://namespace/secret/key`, or
`vault://mount/path#field`. A namespace, tenant name embedded in a path, shared
mount or provider access does not itself grant a tenant access to a reference.
The backend enforces grants when validating tenant-bound configuration and when
resolving the secret at runtime. Provider bootstrap credentials remain internal
to the provider and are not connector-access grants.

Operators must enumerate only the references required by each authorized tenant.
Do not put secret values in this map, source control, workbench URLs or logs.
Providing a reference here is an access-control change: review it together with
the underlying Secret/Vault policy and retain the narrowest scope. No production
tenant or secret has been granted access by the shipped empty example.
