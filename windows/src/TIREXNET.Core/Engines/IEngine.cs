using TIREXNET.Core.Models;

namespace TIREXNET.Core.Engines;

public interface IEngine
{
    string Name { get; }
    /// <summary>Raised when the engine stops without being asked to (crash, adapter removed, …).</summary>
    event Action<string>? Failed;
    Task StartAsync(Profile profile, AppSettings settings, CancellationToken ct);
    /// <summary>Idempotent. Undoes every system change made by <see cref="StartAsync"/>.</summary>
    Task StopAsync();
    Task<(long Upload, long Download)> QueryTotalsAsync();
}
