## Testing

Many features of this project cannot be tested properly. Not everything needs a unit test. Avoid implementing tests that simply verify that code runs as written, or tests that are mock-heavy.

When changing a SUT, verify that the SUT still works with the associated nix smoke test.
