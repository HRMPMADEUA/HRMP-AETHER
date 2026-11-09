using Xunit;

// Several tests touch process-wide state (environment variables, the recovery journal path), so run them serially.
[assembly: CollectionBehavior(DisableTestParallelization = true)]
