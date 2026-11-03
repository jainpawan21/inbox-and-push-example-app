import unittest

from app.novu_service import (
    PushChannel,
    inbox_subscriber_hash,
    merge_device_tokens,
    normalize_token,
    remove_device_token,
    tokens_for_integration,
)


class TokenTests(unittest.TestCase):
    def test_normalize_keeps_case(self) -> None:
        self.assertEqual(normalize_token("  AbC123  "), "AbC123")

    def test_hash_is_lowercase_hex(self) -> None:
        digest = inbox_subscriber_hash("secret", "123456")
        self.assertEqual(len(digest), 64)
        self.assertEqual(digest, digest.lower())
        self.assertNotEqual(inbox_subscriber_hash("other", "123456"), digest)

    def test_merge_adds_and_ignores_a_repeat(self) -> None:
        self.assertEqual(merge_device_tokens(["aa"], " bb "), ["aa", "bb"])
        self.assertEqual(merge_device_tokens(["AbC"], "AbC"), ["AbC"])

    def test_merge_keeps_the_newest_hundred(self) -> None:
        existing = [f"t{index}" for index in range(100)]
        merged = merge_device_tokens(existing, "newest")
        self.assertEqual(len(merged), 100)
        self.assertEqual(merged[0], "t1")
        self.assertEqual(merged[-1], "newest")

    def test_remove_drops_one_token_and_leaves_an_unknown_token(self) -> None:
        self.assertEqual(remove_device_token(["AbC", "other"], " AbC "), ["other"])
        self.assertEqual(remove_device_token(["AbC"], "missing"), ["AbC"])

    def test_integration_filter_ignores_other_providers_and_integrations(self) -> None:
        channels = [
            PushChannel("apns", "fcm-android", ["apple"]),
            PushChannel("fcm", "fcm-web", ["web-token"]),
            PushChannel("fcm", "fcm-android", [" AbC "]),
        ]
        self.assertEqual(tokens_for_integration(channels, "fcm-android"), ["AbC"])
        self.assertEqual(tokens_for_integration(channels, "missing"), [])


if __name__ == "__main__":
    unittest.main()
