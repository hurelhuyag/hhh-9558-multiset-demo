# hibernate-multiset — MULTISET fetch mode for Hibernate ORM 7

A library implementing [HHH-9558](https://hibernate.atlassian.net/browse/HHH-9558): collections
annotated `@MultisetFetch` are loaded as **one nested value in the owner's select list** —
`array_agg(ROW(...))` on PostgreSQL, `json_arrayagg(json_array(...))` on MySQL/MariaDB — so any
number of collections (and their to-ones, and nested collections) load in **one statement**, one
row per owner, no cartesian product, as ordinary managed entities/collections. No changes to
hibernate-core: it plugs in through the persister/fetch extension points.

## Use

```xml
<dependency>
  <groupId>dev.hurlee</groupId>
  <artifactId>hibernate-multiset</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<!-- MySQL / MariaDB only -->
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
</dependency>
```
```properties
hibernate.persister.resolver = dev.hurlee.hibernate.multiset.MultisetPersisterClassResolver
# PostgreSQL: binary transfer for record[] (pgjdbc)
jakarta.persistence.jdbc.url = jdbc:postgresql://host/db?binaryTransferEnable=2287&prepareThreshold=-1
# MariaDB: json_arrayagg is built on group_concat
jakarta.persistence.jdbc.url = jdbc:mariadb://host/db?sessionVariables=group_concat_max_len=4294967295
```
```java
@OneToMany(mappedBy = "order")          // lazy as mapped ...
@MultisetFetch
private List<OrderLine> lines;          // ... loaded as a multiset whenever it is fetched eagerly:
                                        // fetch = EAGER, an entity graph, a fetch profile
```
### PostgreSQL setup

Nothing is needed on the server: `array_agg(row(...))` and the binary form of `record[]` exist in every
supported PostgreSQL version. What matters is the **client**: pgjdbc must be asked to transfer
`record[]` in *binary* wire format, because that is the only format in which an anonymous record
carries the type of every field (in text format it is an untyped literal like `{"(1,a)"}`, which the
library refuses with `IllegalStateException: record[] arrived in text format ...`).

Two driver properties, both mandatory:

| Property | Value | Why |
|----------|-------|-----|
| `binaryTransferEnable` | `2287` | the OID of `record[]`; adds it to the set of types pgjdbc requests in binary. Comma-separate if you already list others (`binaryTransferEnable=2287,3802`). |
| `prepareThreshold` | `-1` | pgjdbc uses binary transfer only on server-side prepared statements. The default threshold (5) would send the first four executions of each statement in text — and fail. `-1` prepares server-side from the first execution; `1` also works (binary from the second execution is too late for the first). |

Where to put them depends on how the connection is made:

```properties
# plain JDBC URL (any provider)
jdbc:postgresql://host:5432/db?binaryTransferEnable=2287&prepareThreshold=-1

# Hibernate's built-in connection provider: hibernate.connection.* is forwarded to the driver
hibernate.connection.binaryTransferEnable = 2287
hibernate.connection.prepareThreshold = -1

# HikariCP via Hibernate (hibernate-hikaricp)
hibernate.hikari.dataSource.binaryTransferEnable = 2287
hibernate.hikari.dataSource.prepareThreshold = -1

# Spring Boot + HikariCP
spring.datasource.hikari.data-source-properties.binaryTransferEnable = 2287
spring.datasource.hikari.data-source-properties.prepareThreshold = -1
```
```java
// programmatic DataSource
PGSimpleDataSource ds = new PGSimpleDataSource();
ds.setBinaryTransferEnable("2287");
ds.setPrepareThreshold(-1);
```

Notes:

- Only `record[]` is affected. Every other column keeps pgjdbc's default handling, and forcing
  server-side prepared statements is what Hibernate benefits from anyway (statement reuse).
- **PgBouncer / connection poolers**: server-side prepared statements need session pooling, or
  PgBouncer ≥ 1.21 with `max_prepared_statements > 0` (protocol-level prepared statement support).
  With plain transaction pooling `prepareThreshold=-1` breaks, and so does this library.
- **pgjdbc ≥ 42.2**; tested with 42.7.x. The R2DBC driver and other JDBC drivers are not supported.
- Quick self-check from psql-less environments: run any `@MultisetFetch` query — a misconfigured
  connection fails on the first fetch with the hint above rather than returning wrong data.

MySQL and MariaDB need no driver flags (MariaDB: raise `group_concat_max_len`, see below), since the
JSON flavour is plain text.

Requires Hibernate ORM 7.4+, JDK 17+; `hibernate-core` is `provided`. Any immediate fetch of the
collection becomes a multiset; an explicit HQL `join fetch` stays a join; otherwise the collection is
as lazy as its mapping says. Supported: `@OneToMany`, `@ManyToMany`, `@ElementCollection` (basic,
embeddable, map), Set/List(`@OrderColumn`)/Bag/Map, `@OrderBy` (rendered inside the aggregate, with
Hibernate's per-dialect null-precedence handling; sorted client-side on MySQL, which cannot order
inside `json_arrayagg`), eager to-ones inside the element (joined inside the subquery), nested
`@MultisetFetch` collections. Not supported: idbag; other databases fail fast.

## Build and test

```
mvn verify
```
`docker-maven-plugin` starts `postgres:17-alpine`, `mysql:8.4` and `mariadb:11.8` on random ports
before the integration tests and stops them afterwards. Requires Docker. The test entities
(`src/test/java/demo/<case>`) are not part of the jar.

`mvn verify` also runs `demo.ArrayAggRowExample`, a standalone JDBC `main()` showing the
`array_agg(ROW(...))` round-trip (binary `record[]` → `PgBinaryRecordArray` → typed records).
It can be pointed at any PostgreSQL with `-Dpg.url=... -Dpg.user=... -Dpg.password=...`.

| Test | What it proves |
|------|----------------|
| `BinaryRecordArrayIT` | `array_agg(ROW(...))` requested in **binary** wire format (`binaryTransferEnable=2287&prepareThreshold=-1`): `record_send` puts every field's type OID on the wire, so `PgBinaryRecordArray` decodes it into a typed `Object[][]` (int/long/BigDecimal/String/UUID/LocalDate/…) with no string parsing and no out-of-band type info. |

## Layout

| Package | Case | Test |
|---------|------|------|
| `dev.hurlee.hibernate.multiset` (src/main) | the library: fetch-mode implementation, `PgBinaryRecordArray` decoder | — |
| `demo.onetomany` | 1: `@OneToMany(fetch = EAGER) @MultisetFetch` — `Author -> books/reviews`, `@OrderColumn`, lazy to-one | `MultisetFetchIT` |
| `demo.graph` | 2: lazy + named entity graph — `Order -> lines -> product -> category`, nested `line -> discounts` | `OrderLinesProductIT` |
| `demo.elementcollection` | 3: `@ElementCollection` — `Customer` phone numbers (Set), addresses (List of embeddables), attributes (Map) | `ElementCollectionIT` |
| `demo.manytomany` | 4: `@ManyToMany` — `Role <-> Permission` | `ManyToManyIT` |
| `demo` (src/test) | `ArrayAggRowExample`, `TestDb`; all ITs, the four case ITs parameterized over PostgreSQL / MySQL / MariaDB | `BinaryRecordArrayIT` |

## `@OneToMany` MULTISET fetch mode — without changing hibernate-core

`dev.hurlee.hibernate.multiset` implements an eager collection fetch strategy that loads real managed child
entities from one `array_agg(ROW(...))` column of the owner's statement, using only the extension
points Hibernate ships (the same ones Hibernate Reactive uses):

```java
@OneToMany(mappedBy = "author", fetch = FetchType.EAGER)
@MultisetFetch                      // -> FetchMode.MULTISET in a real patch
private List<Book> books;
```
```
hibernate.persister.resolver = dev.hurlee.hibernate.multiset.MultisetPersisterClassResolver
jdbc url                     = ...?binaryTransferEnable=2287&prepareThreshold=-1
```
```sql
select a1_0.id, a1_0.name,
       (select array_agg(row(b1_0.id, b1_0.author_id, b1_0.pages, b1_0.publisher_id, b1_0.title, b1_0.position)
                         order by b1_0.position) from Book b1_0 where a1_0.id=b1_0.author_id),
       (select array_agg(row(r1_0.id, r1_0.author_id, r1_0.stars, r1_0.text) order by r1_0.id)
          from Review r1_0 where a1_0.id=r1_0.author_id)
  from Author a1_0 where a1_0.id=?
```

| Class | Hook used |
|-------|-----------|
| `MultisetPersisterClassResolver` | `hibernate.persister.resolver` service |
| `MultisetEntityPersister` | protected `AbstractEntityPersister.buildPluralAttributeMapping` + the `mappingConverter` parameter of `MappingModelCreationHelper.buildPluralAttributeMapping` |
| `MultisetPluralAttributeMapping` | protected copy constructor of `PluralAttributeMappingImpl`; overrides `generateFetch` and `getSelectableIndex` (defines the `ROW(...)` shape) |
| `MultisetCollectionFetch` | `CollectionFetch`; registers a detached collection table group, builds the correlated subquery as a SQL AST `SelectStatement` and one outer `SqlSelection`; creates the element entity fetch through `DomainResultCreationState.visitNestedFetches`, so child columns resolve to record positions |
| `MultisetAggregateExpression` | `SelfRenderingExpression` rendering `array_agg(row(...) order by ...)` |
| `MultisetCollectionInitializer` | `AbstractNonJoinCollectionInitializer`; drives the element `EntityInitializerImpl` once per decoded record and feeds `LoadingCollectionEntryImpl` |
| `MultisetRowProcessingState` | nested `RowProcessingState` serving record fields by position (cf. `NestedRowProcessingState`) |
| `NestedAssemblerCreationState` | keeps the element initializer tree out of the row reader's flat list |
| `MultisetToOneAttributeMapping` / `RowSegment` | copy constructor of `ToOneAttributeMapping` + the `mappingConverter` of `buildSingularAssociationAttributeMapping`; an EAGER to-one met inside a multiset element is joined *inside* the subquery and its columns appended to the `ROW` as a segment (recursively: `Order -> lines -> product -> category`) |
| `RecordArrayJdbcType` / `RecordArrayJavaType` | `record[]` binary decoding as a Hibernate `BasicType` |

`MultisetFetchIT` covers `find()`, HQL, two bags on one entity, empty collections, dirty checking /
orphan removal on the loaded bag, an explicit `join fetch` coexisting with a multiset bag,
`@OrderColumn` lists, a lazy `@ManyToOne` inside the element (FK from the record -> proxy), NULL
fields and quoting-hostile strings.

`@MultisetFetch` does not change *when* a collection is fetched, only *how*: every immediate fetch
of the collection — `fetch = EAGER`, an entity graph (fetch or load semantic, HQL or `find()` hint)
or a fetch profile — becomes a multiset; an explicit HQL `join fetch` stays a join, and otherwise
the collection is as lazy as its mapping says. The SQM converter joins graph-driven fetches before
asking for the fetch, so `MultisetPluralAttributeMapping` detaches that join from the owner (by
reflection, in this PoC) and reuses it inside the subquery.

`OrderLinesProductIT` covers the everyday shape `Order -> lines -> product -> category` with lazy
`lines`/`product` and the named graph `Order.withProducts` declared on `Order`, via HQL and `find()`,
in one statement (with `GraphSemantic.LOAD`, the EAGER `category` is folded in as well):

```sql
select o1_0.id, o1_0.customer,
  (select array_agg(row(l1_0.id, l1_0.order_id, l1_0.product_id, l1_0.quantity,
                        p1_0.id, p1_0.category_id, p1_0.price, p1_0.sku, c1_0.id, c1_0.name) order by l1_0.id)
     from OrderLine l1_0
     left join Product p1_0 on p1_0.id=l1_0.product_id
     left join Category c1_0 on c1_0.id=p1_0.category_id
    where o1_0.id=l1_0.order_id)
from orders o1_0 where o1_0.id=?
```

`ElementCollectionIT` covers `@ElementCollection`s on `Customer`: a `Set<String>` of phone numbers,
an `@OrderColumn` `List<Address>` of embeddables and a `Map<String, String>`, all three in the
customer's statement, and writable afterwards (dirty checking on the loaded collections):

```sql
select c1_0.id, c1_0.name,
  (select array_agg(row(pn1_0.number)) from customer_phone pn1_0 where c1_0.id=pn1_0.Customer_id),
  (select array_agg(row(a1_0.city, a1_0.street, a1_0.position) order by a1_0.position)
     from customer_address a1_0 where c1_0.id=a1_0.Customer_id),
  (select array_agg(row(a2_0.attr_value, a2_0.attr_key) order by a2_0.attr_key)
     from customer_attribute a2_0 where c1_0.id=a2_0.Customer_id)
from Customer c1_0 where c1_0.id=?
```

`ManyToManyIT` covers `Role <-> Permission` through a join table (`@ManyToMany(fetch = EAGER)`):

```sql
select r1_0.id, r1_0.name,
  (select array_agg(row(p1_1.id, p1_1.code) order by p1_1.id)
     from role_permission p1_0 join Permission p1_1 on p1_1.id=p1_0.permissions_id
    where r1_0.id=p1_0.Role_id)
from Role r1_0 where r1_0.id=?
```
A permission shared by several roles is one managed instance, and join-table rows can be added/removed on the loaded set.

A `@MultisetFetch` collection inside a multiset element (`Order -> lines -> discounts`) becomes a
column of the enclosing `ROW` — a `record[]` inside the record — so the whole graph is still one
statement (`OrderLinesProductIT.nestedCollection_insideTheElement_isPartOfTheSameStatement`):

```sql
(select array_agg(row(l1_0.id, l1_0.order_id, l1_0.product_id, l1_0.quantity,
                      (select array_agg(row(d1_0.id, d1_0.amount, d1_0.line_id, d1_0.reason) order by d1_0.id)
                         from Discount d1_0 where l1_0.id=d1_0.line_id),
                      p1_0.id, p1_0.category_id, p1_0.price, p1_0.sku, c1_0.id, c1_0.name) order by l1_0.id)
   from OrderLine l1_0 left join Product p1_0 on ... left join Category c1_0 on ...
  where o1_0.id=l1_0.order_id)
```

### MySQL / MariaDB: `json_arrayagg(json_array(...))`

`MultisetDialect` picks the spelling per dialect. On MySQL and MariaDB the multiset is a JSON array of
*positional* arrays — `json_array(c1, c2, ...)` instead of `json_object('c1', ..)`: no repeated keys,
and the ROW shape already gives every position, so `JsonRecordArrayJdbcType` decodes by index exactly
like the PostgreSQL record. A nested multiset is a nested JSON array; eager to-ones are folded in the same way:

```sql
select o1_0.id, o1_0.customer,
  (select json_arrayagg(json_array(l1_0.id, l1_0.order_id, l1_0.product_id, l1_0.quantity,
                                   (select json_arrayagg(json_array(d1_0.id, d1_0.amount, d1_0.line_id, d1_0.reason))
                                      from Discount d1_0 where l1_0.id=d1_0.line_id),
                                   p1_0.id, p1_0.category_id, p1_0.price, p1_0.sku, c1_0.id, c1_0.name))
     from OrderLine l1_0 left join Product p1_0 on ... left join Category c1_0 on ...
    where o1_0.id=l1_0.order_id)
from orders o1_0 order by o1_0.customer
```

Differences from the record path, handled in `MultisetRowProcessingState.coerce`: JSON carries no
decimal scale (restored from the mapping / Hibernate's default), temporal values arrive as strings
(parsed through the attribute's `JavaType`), and MariaDB may hand a nested multiset back as a JSON
string (parsed). MariaDB supports `ORDER BY` inside `json_arrayagg`, MySQL does not (lists are placed
by their index column anyway); MariaDB's `json_arrayagg` is built on `group_concat`, so the test URL
raises `group_concat_max_len`. The four case ITs are `@ParameterizedClass`es over
`TestDb.databases()` = PostgreSQL, MySQL, MariaDB (`mysql:8.4` and `mariadb:11.8` are started by
docker-maven-plugin next to PostgreSQL), so every case runs unchanged on all three; only
`BinaryRecordArrayIT` (the binary decoder) is PostgreSQL-only.

Known limits of the PoC: PostgreSQL, MySQL and MariaDB only; no idbag; `@SQLRestriction`/filters, inheritance of the
element entity and caches are untested.
