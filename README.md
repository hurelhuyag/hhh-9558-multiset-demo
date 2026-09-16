# HHH-9558 — MULTISET emulation on PostgreSQL

Proof-of-concept for [HHH-9558](https://hibernate.atlassian.net/browse/HHH-9558): several bag
collections fetched in **one** SQL statement, one row per parent, no cartesian product, by
returning each collection as a nested value in the projection.

```
mvn verify
```

`docker-maven-plugin` starts `postgres:17-alpine` on a random port before the integration tests
and stops it afterwards. Requires Docker and JDK 17+.

`mvn verify` also runs `demo.ArrayAggRowExample`, a standalone JDBC `main()` showing the
`array_agg(ROW(...))` round-trip (binary `record[]` → `PgBinaryRecordArray` → typed records).
It can be pointed at any PostgreSQL with `-Dpg.url=... -Dpg.user=... -Dpg.password=...`.

| Test | What it proves |
|------|----------------|
| `BinaryRecordArrayIT` | `array_agg(ROW(...))` requested in **binary** wire format (`binaryTransferEnable=2287&prepareThreshold=-1`): `record_send` puts every field's type OID on the wire, so `PgBinaryRecordArray` decodes it into a typed `Object[][]` (int/long/BigDecimal/String/UUID/LocalDate/…) with no string parsing and no out-of-band type info. |
| `HibernateMultisetEmulationIT.joinFetchingTwoBags…` | Baseline: `join fetch` of two bags is refused with `MultipleBagFetchException`. |
| `HibernateMultisetEmulationIT.multisetEmulation…` | With `@Formula("(select array_agg(row(...)) ...)")` + `@Type(RecordArrayType.class)` (a `UserType` that feeds the binary `record[]` into a Java `record`'s canonical constructor), Hibernate 7 loads both bags for all users in **exactly one** prepared statement (checked via `Statistics`). |
