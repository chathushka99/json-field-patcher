# JsonPathUpdater

A lightweight Java utility for reading and writing arbitrarily nested JSON fields using dot-notation path expressions - designed for fintech systems that store structured JSON documents inside relational databases (MySQL, PostgreSQL, Oracle) without schema migrations.

---

## The Problem It Solves

### JSON in Relational Databases - Without the Schema Pain

In many fintech and banking systems, product and customer data models evolve constantly. A **KYC profile**, a **payment instruction template**, or a **trading account configuration** might start with 10 fields and grow to 100 over 18 months.

The traditional relational approach forces you to:

- Write a migration script for every new field
- Alter production tables with `ALTER TABLE` statements
- Redeploy services to handle new column mappings
- Maintain ORM entity classes in sync with the schema

One common alternative is to store a **JSON blob in a single `TEXT` or `JSON` column** and update individual fields inside it without touching the schema at all. This is a valid and widely used pattern in production -but it brings its own challenge: **how do you read and write individual fields deep inside that JSON without rewriting the whole document every time?**

That is exactly what this library solves.

### Concrete Example: Banking Customer Profile in MySQL

```sql
CREATE TABLE customer_profile (
    customer_id   VARCHAR(36) PRIMARY KEY,
    profile_data  TEXT,          -- stores the entire JSON document
    updated_at    TIMESTAMP
);
```

```json
{
  "personal": {
    "name": "Gayan Perera",
    "dob": { "$date": "1990-01-01" }
  },
  "accounts": [
    { "type": "SAVINGS", "currency": "JPY", "status": "ACTIVE" },
    { "type": "TRADING", "currency": "USD", "status": "PENDING" }
  ],
  "kyc": {
    "tier": "STANDARD",
    "verified": false
  }
}
```

When the compliance team adds a new field -say `kyc.aml_score` -you do **not** need to alter the table. You just write to the new path. If it doesn't exist, `JsonPathUpdater` creates the full nested structure automatically.

---

## Why Not Use JsonPath (Jayway)?

[Jayway JsonPath](https://github.com/json-path/JsonPath) is an excellent library for **reading** values. But it has limitations that matter in write-heavy, schema-flexible production systems:

| Capability | Jayway JsonPath | **JsonPathUpdater** |
|---|---|---|
| Read values by path | ✅ | ✅ |
| Write values by path | ✅ (basic) | ✅ |
| **Auto-create missing intermediate nodes** | ❌ Manual | ✅ Automatic |
| **Auto-create missing array slots** | ❌ | ✅ Pads with empty objects |
| **No external dependency** | ❌ Requires Jayway + provider | ✅ Jackson only |
| Field type control (scalar vs array leaf) | ❌ | ✅ via `fieldType` |
| Path compilation + cache | ✅ | ✅ `ConcurrentHashMap` |
| Parse once, serialize once | ✅ | ✅ |

The key differentiator is **auto-creation of missing structure**. In a database-backed JSON store, a new field path might not exist yet in older records. `JsonPathUpdater` walks the path and creates every missing `ObjectNode` or `ArrayNode` along the way - so you never get a `PathNotFoundException` when writing to a new template field.

---

## How It Works

Paths use a simple dot-notation with bracket syntax for array indices:

```
"kyc.status"                       → nested object field
"accounts[1].currency"             → array element field  
"accounts[0].limits.daily.$amount" → deeply nested with special-char key
```

Internally:

1. **Parse once** - the JSON string is parsed into a Jackson `ObjectNode` tree.
2. **Compile path once** - the path string is split into `PathSegment[]` objects and cached in a `ConcurrentHashMap`. Subsequent calls with the same path pay only a lock-free map lookup.
3. **Walk iteratively** -no recursion, no intermediate serialization. The tree is traversed with a simple `for` loop, creating missing nodes en route.
4. **Write leaf** - the final value is written directly into the in-memory tree.
5. **Serialize once** - `MAPPER.writeValueAsString(root)` produces the final JSON string.

The result is saved back to the database column. No schema changes, no migrations, no redeployment.

---

## Quick Start

### Dependency

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.1</version>
</dependency>
```

---

## Examples

### 1. Update a Date Field Inside an Array Element

**Use case:** Compliance updates a customer's date-of-birth after a KYC correction.

```java
String profileJson = """
    {
      "ch": [
        {
          "ch_g": "1",
          "ch_bd": { "$date": "1900-01-01" }
        }
      ]
    }
    """;

String updated = JsonPathUpdater.updateField(
    profileJson,
    "ch[0].ch_bd.$date",
    List.of("1990-06-15"),
    JsonPathUpdater.FIELD_TYPE_SCALAR
);
// → {"ch":[{"ch_g":"1","ch_bd":{"$date":"1990-06-15"}}]}
```

---

### 2. Write to a Path That Does Not Exist Yet

**Use case:** A new `aml_score` field is added to the KYC template. Older customer records in the database don't have it yet. Writing to the path creates the full structure automatically.

```java
String existingProfile = """
    {
      "kyc": { "tier": "STANDARD", "verified": false }
    }
    """;

String withAml = JsonPathUpdater.updateField(
    existingProfile,
    "kyc.aml.score",
    List.of("72"),
    JsonPathUpdater.FIELD_TYPE_SCALAR
);
// → {"kyc":{"tier":"STANDARD","verified":false,"aml":{"score":"72"}}}
```

No `PathNotFoundException`. No pre-migration of existing rows required.

---

### 3. Write to a Missing Array Slot

**Use case:** A payment instruction template gains a second beneficiary. The array slot doesn't exist in older records – it is auto-created and padded.

```java
String instruction = """
    {
      "beneficiaries": [
        { "name": "Alice", "bank": "MUFG" }
      ]
    }
    """;

String withSecond = JsonPathUpdater.updateField(
    instruction,
    "beneficiaries[1].name",
    List.of("Bob"),
    JsonPathUpdater.FIELD_TYPE_SCALAR
);
// → {"beneficiaries":[{"name":"Alice","bank":"MUFG"},{"name":"Bob"}]}
```

---

### 4. Write an Array of Values to a Leaf Field

**Use case:** Store a list of permitted currencies on a trading account configuration.

```java
String account = """
    { "account_id": "TRD-001", "status": "ACTIVE" }
    """;

String withCurrencies = JsonPathUpdater.updateField(
    account,
    "permitted_currencies",
    List.of("JPY", "USD", "SGD"),
    JsonPathUpdater.FIELD_TYPE_ARRAY
);
// → {"account_id":"TRD-001","status":"ACTIVE","permitted_currencies":["JPY","USD","SGD"]}
```

---

### 5. Read a Value Back from the Stored JSON

**Use case:** Retrieve the KYC verification status for a display layer without deserializing the entire object.

```java
String profile = """
    {
      "kyc": { "tier": "ENHANCED", "verified": true, "score": "88" }
    }
    """;

List<String> score = JsonPathUpdater.readField(profile, "kyc.score");
// → ["88"]

List<String> tier = JsonPathUpdater.readField(profile, "kyc.tier");
// → ["ENHANCED"]
```

---

### 6. Read All Elements from an Array

**Use case:** Retrieve every IBAN stored in a payment instruction without index traversal logic in the caller.

```java
String instruction = """
    {
      "ibans": ["DE89370400440532013000", "GB33BUKB20201555555555"]
    }
    """;

List<String> ibans = JsonPathUpdater.readField(instruction, "ibans");
// → ["DE89370400440532013000", "GB33BUKB20201555555555"]
```

---

### 7. Typical MySQL Round-Trip (JDBC)

```java
// FETCH
String profileJson = jdbcTemplate.queryForObject(
    "SELECT profile_data FROM customer_profile WHERE customer_id = ?",
    String.class, customerId
);

// PATCH - update KYC status without touching any other field
String updated = JsonPathUpdater.updateField(
    profileJson,
    "kyc.status",
    List.of("VERIFIED"),
    JsonPathUpdater.FIELD_TYPE_SCALAR
);

// PERSIST
jdbcTemplate.update(
    "UPDATE customer_profile SET profile_data = ?, updated_at = NOW() WHERE customer_id = ?",
    updated, customerId
);
```

No schema change. No migration. No redeployment.

---

## Real-World Use Cases

| Domain | Scenario |
|---|---|
| **KYC / Compliance** | Add new regulatory fields (`aml_score`, `pep_flag`, `fatca_status`) to existing customer records without schema migrations |
| **Payment Templates** | Store flexible SWIFT/SEPA instruction templates in one column; update individual fields (BIC, IBAN, charge bearer) per transaction |
| **Trading Account Config** | Persist per-account risk parameters and permitted instrument lists; update individual limits without rebuilding the full object |
| **Product Catalogue** | Store variable-attribute products (term deposits, FX options) with different field shapes in one table column |
| **Audit / Event Enrichment** | Enrich incoming FIX or JSON messages with computed fields (e.g. `risk.var`, `fees.calculated`) before persisting to an event store |
| **Mobile Onboarding** | Progressive profile completion -each onboarding step writes its fields without knowing what the other steps wrote |

---

## Thread Safety

`JsonPathUpdater` is fully thread-safe for concurrent use:

- `ObjectMapper` is a singleton; Jackson's `readTree` and `writeValueAsString` are thread-safe.
- `PATH_CACHE` is a `ConcurrentHashMap` -lock-free reads in the hot path.
- No shared mutable state between calls.

---

## Performance Notes

- **Parse once, serialize once** - no intermediate `toString()` / re-parse cycles.
- **In-place tree mutation** -Jackson `ObjectNode` / `ArrayNode` are mutable; no string replacement or document rebuilding.
- **Path caching** - `"kyc.aml.score"` is parsed into `PathSegment[]` once; all subsequent calls pay only a map lookup.
- **Zero recursion** - iterative tree walk; no JVM stack overhead for deep paths.

---