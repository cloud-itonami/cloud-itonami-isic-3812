# Security Policy

This project handles hazardous-waste manifest and chain-of-custody
workflows. Treat vulnerabilities as potentially high impact even when
the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- manifest/credential exposure
- real generator or customer data exposure
- authorization bypass
- Hazardous Waste Governor bypass
- audit-ledger tampering
- over-disclosure in chain-of-custody reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on manifest data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real generator and manifest data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
