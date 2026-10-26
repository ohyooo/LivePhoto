// CI-only: launch the portable GUI with an invalid external JAVA_HOME and inspect actual pixels.
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

class DesktopPackageSmoke {
    public static void main(String[] args) throws Exception {
        Path launcher = Path.of(args[0]).toAbsolutePath();
        Path reports = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(reports);
        var size = Toolkit.getDefaultToolkit().getScreenSize();
        var bounds = new Rectangle(0, 0, Math.min(size.width, 1280), Math.min(size.height, 840));
        var robot = new Robot();
        BufferedImage before = robot.createScreenCapture(bounds);
        Path log = reports.resolve("packaged-gui.log");
        var command = new ProcessBuilder(launcher.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        command.environment().put("JAVA_HOME", reports.resolve("no-external-java").toString());
        Process process = command.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        BufferedImage actual = null;
        int changed = 0, blue = 0;
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(200);
                actual = robot.createScreenCapture(bounds);
                changed = 0; blue = 0;
                // Exclude window borders, title/menu bars and the OS dock/taskbar.
                for (int y = 80; y < Math.min(600, bounds.height - 140); y++) for (int x = 32; x < bounds.width - 32; x++) {
                    int rgb = actual.getRGB(x, y) & 0xffffff;
                    if (rgb != (before.getRGB(x, y) & 0xffffff)) {
                        changed++;
                        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                        if (b > 100 && b > r + 30 && b > g + 20) blue++;
                    }
                }
                if (changed > 50000 && blue > 100) break;
            }
            if (actual != null) ImageIO.write(actual, "png", reports.resolve("packaged-gui.png").toFile());
            if (!process.isAlive() || changed <= 50000 || blue <= 100) {
                throw new IllegalStateException("Packaged Compose GUI did not render: alive=" + process.isAlive() + ", changed=" + changed + ", blue=" + blue + "\n" + Files.readString(log));
            }
            Files.writeString(reports.resolve("packaged-gui.json"), "{\"rendered\":true,\"externalJavaDisabled\":true,\"changedPixels\":" + changed + ",\"bluePixels\":" + blue + "}\n");
            System.out.println("PACKAGED_DESKTOP_GUI=VERIFIED");
        } finally {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Packaged GUI was not reaped");
            }
        }
    }
}
