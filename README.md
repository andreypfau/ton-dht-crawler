# ton-dht-crawler

CLI tool for crawling nodes in the TON DHT network.

Connects to bootstrap nodes, recursively queries peers, and outputs results in JSON.

---

## Features

- Crawls TON DHT starting from bootstrap nodes
- Uses coroutines for parallelism
- Outputs discovered nodes to JSON file

---

## Usage

```bash
./ton-dht-crawler crawl --output ./result.json
```

### Options

| Flag           | Description                 | Default |
| -------------- | --------------------------- | ------- |
| `-o, --output` | Output file path (required) | —       |
| `-w, --worker` | Number of parallel workers  | 1000    |
| `--version`    | Show version                |         |

---

## Output Format

File is saved as a JSON array of crawl results:

```json
[
  {
    "info": {
      "id": {
        "@type": "pub.ed25519",
        "key": "..."
      },
      "addr_list": {
        "addrs": [
          {
            "@type": "adnl.address.udp",
            "ip": 123456789,
            "port": 30100
          }
        ],
        "version": 123456,
        "reinit_date": 123456
      },
      "version": 123456,
      "signature": "..."
    },
    "success_connection": true,
    "time": "2025-08-04T00:00:00Z",
    "duration": "PT1.5S",
    "drain_result": [
      {
        "id": {
          "@type": "pub.ed25519",
          "key": "..."
        }
      },
      ...
    ]
  },
  ...
]
```

- `success_connection`: whether the initial peer responded to queries
- `time`: timestamp when draining began (start of peer discovery)
- `duration`: how long the crawling process took for this node
- `drain_result`: list of peers discovered via this node
- durations follow ISO-8601 format (`PT1.5S` = 1.5 seconds)

---

## Building

To build for macOS:

```bash
./gradlew linkReleaseExecutableMacosArm64
```

Change the target (`macosArm64`) to match your platform.
