# RAR test fixtures

Real archives used by `RarKnowledgeExtractionTest` (CI has no `rar` binary, so
they are committed rather than generated).

Made with RAR 7.00 (RAR5 format) from the same content as `MyShopFixture`:

| File | How | What it covers |
|------|-----|----------------|
| `my-shop.rar` | `rar a -ma5 -s -r` of `MyShopFixture.zip()` unpacked | solid RAR5 project — must read exactly like the ZIP |
| `course.rar` | `rar a -ma5` of a DOCX + PDF | documents inside a RAR |
| `password.rar` | `rar a -ma5 -p…` | encrypted file data |
| `header-encrypted.rar` | `rar a -ma5 -hp…` | encrypted headers (file names hidden) |
| `multipart.part{1,2,3}.rar` | `rar a -ma5 -m0 -v4k` | one volume of a multi-part archive |
| `symlink.rar` | `rar a -ma5 -ol`, `evil.md -> /etc/passwd` | links are never followed |
| `only-dirs.rar` | `rar a -ma5 -r` of empty folders | empty archive |
| `traversal.rar` | `rar a -ma5 -ap'../../..' evil.md` | entry named `../../../evil.md` |
| `bomb.rar` | 4 MB of zeros + `ok.md` | compression-ratio guard |

RAR4 (RAR 2.9 format) — current `rar` can no longer create it; these two come
from the test suite of [rarfile](https://github.com/markokr/rarfile)
(`rar3-solid.rar`, `rar3-subdirs.rar`):

> Copyright (c) 2005-2024 Marko Kreen <markokr@gmail.com>
>
> Permission to use, copy, modify, and/or distribute this software for any
> purpose with or without fee is hereby granted, provided that the above
> copyright notice and this permission notice appear in all copies.

`../zip/password.zip` — `zip -P secret` (ZIP counterpart of `password.rar`).
