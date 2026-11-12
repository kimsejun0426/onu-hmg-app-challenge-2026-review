"""Independent published identities and path expectations for the content release."""

PUBLISHED_IDS = {
    "dinosaur-lab-missing-egg", "starlight-post-office", "undersea-lighthouse",
    "space-return-signal", "animal-footprint-garden", "little-car-workshop",
}
NEW_PACK_ID = "onu-little-picnic"
EDITED_012_IDS = PUBLISHED_IDS - {"little-car-workshop"}
CURRENT_VERSIONS = {
    "dinosaur-lab-missing-egg": "0.12.0", "starlight-post-office": "0.9.0",
    "undersea-lighthouse": "0.9.0", "space-return-signal": "0.11.0",
    "animal-footprint-garden": "0.12.0", "little-car-workshop": "0.11.0", NEW_PACK_ID: "0.6.0",
}
LEGACY_IDENTITIES = {(pack_id, version) for pack_id in PUBLISHED_IDS
                     for version in ("0.1.0", "0.2.0", "0.3.0", "0.4.0", "0.5.0")} | {
                         (pack_id, "0.6.0") for pack_id in EDITED_012_IDS
                     } | {(NEW_PACK_ID, "0.1.0"), ("space-return-signal", "0.7.0"),
                          ("little-car-workshop", "0.6.0"), (NEW_PACK_ID, "0.2.0"),
                          ("animal-footprint-garden", "0.7.0"), ("little-car-workshop", "0.7.0"),
                          ("animal-footprint-garden", "0.8.0"), ("little-car-workshop", "0.8.0"),
                          ("space-return-signal", "0.8.0"), ("animal-footprint-garden", "0.9.0"),
                          (NEW_PACK_ID, "0.3.0"), ("undersea-lighthouse", "0.7.0"),
                          ("dinosaur-lab-missing-egg", "0.7.0"),
                          ("starlight-post-office", "0.7.0"),
                          ("undersea-lighthouse", "0.8.0"),
                          ("space-return-signal", "0.9.0"),
                          ("animal-footprint-garden", "0.10.0"),
                          ("little-car-workshop", "0.9.0"),
                          ("onu-little-picnic", "0.4.0"),
                          ("dinosaur-lab-missing-egg", "0.8.0"),
                          ("space-return-signal", "0.10.0"),
                          ("animal-footprint-garden", "0.11.0"),
                          ("little-car-workshop", "0.10.0"),
                          ("dinosaur-lab-missing-egg", "0.9.0"),
                          ("starlight-post-office", "0.8.0"), (NEW_PACK_ID, "0.5.0"),
                          ("dinosaur-lab-missing-egg", "0.10.0"), ("dinosaur-lab-missing-egg", "0.11.0")}
RETIRED_ANIMAL_WAIT_WORDS = {"잠깐 기다리기", "기다리기", "조금 더", "기다릴래"}
# Exact source bytes and path-independent catalog metadata read before the 0.8
# animal/car rewrite; never regenerate these from the new archived files.
PUBLISHED_CONTEXT_070 = {
    "animal-footprint-garden": (19493, "aa2c481fab05ef14b8eea79da071b036a83a508c341213689ece33c6ed9827fa",
                                "08d6be9388e785f55418996c6c321f8484ccc4eda2de9565df5853c421dad929"),
    "little-car-workshop": (19921, "a4338e44267118017a7ac0154c3b42ed9a9dd1ded1b0b40fc1d3f9f77332da94",
                            "313d844f7639d61f6ad8fca079bfe310b9ec155bf1a5572edf88810c84891674"),
}
# SHA-256 of the six published 0.4.0 files read from Git HEAD before the rewrite.
PUBLISHED_040 = {
    "dinosaurs": "eded344d066055d8a1e974eb51941c5034338607b148eb9a29b01181bfe19176",
    "starlight-post-office": "49f5c34319cca0591463ef88913aff4138939fb9f82ca987604f105bd7ff98fb",
    "undersea-lighthouse": "e7b372fbd79e3b8cd43142183539b294966d9e67ebc8a44ddaf3c5e72ddf4673",
    "space-return-signal": "59f2f84c947526def3a9f1ca488ef36f4f072b7fcf7b993de1bfba7be7617e4f",
    "animal-footprint-garden": "e01cae1687ea634dd1922f693721b76bde18f2b9705a2d4c8e3937b44323a818",
    "little-car-workshop": "7260440baf4d32a1e1f91b633eebcf8719476bb09a0dca71cadba70f5c76217c",
}
PUBLISHED_040_METADATA = {
    "dinosaur-lab-missing-egg": "150c1caedb4c9ad3f72cd512012d886e6b0987d051f5b03234b3a79976f4ba17",
    "starlight-post-office": "36aa16cbc937cc5db463962cee835e70d10b3faee2c2bc5619eb3ecbc44b17e0",
    "undersea-lighthouse": "9771956bb98301e5db06fd342f895a4db9f4aac689323c879292f68934432a24",
    "space-return-signal": "b9798b2e1d9d2ec4f21902aeda653d5967103c8293a26d4fb736e89bb3730c4b",
    "animal-footprint-garden": "aadb715e1e4cd43c2a1862973c26f69a9261e8f7febcac7a561b20697c50734b",
    "little-car-workshop": "244e86da6f397d38602c0dd68c4b3b566b2b2e731450456dbe39c58526c465d7",
}
