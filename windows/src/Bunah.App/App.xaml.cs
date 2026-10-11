using System.IO;
using System.Windows;
using System.Windows.Threading;

namespace Bunah.App;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += OnUnhandled;
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
            Log(args.ExceptionObject as Exception, "AppDomain");
    }

    void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Log(e.Exception, "Dispatcher");
        MessageBox.Show(
            "Aplikasi mengalami error dan akan ditutup.\n\n" +
            $"Detail disimpan ke:\n{LogPath}\n\n" +
            "Kirim file itu ke pengelola aplikasi.",
            "Bunah", MessageBoxButton.OK, MessageBoxImage.Error);
        e.Handled = false;
    }

    static string LogPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Bunah", "crash.log");

    public static void Log(Exception? ex, string source)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
            File.AppendAllText(LogPath,
                $"--- {DateTime.Now:yyyy-MM-dd HH:mm:ss} [{source}] ---\n{ex}\n\n");
        }
        catch { }
    }
}
