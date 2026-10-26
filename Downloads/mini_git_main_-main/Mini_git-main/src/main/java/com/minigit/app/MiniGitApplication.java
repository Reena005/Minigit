package com.minigit.app;

import com.minigit.command.AddCommand;
import com.minigit.command.BranchCommand;
import com.minigit.command.CheckoutCommand;
import com.minigit.command.CommitCommand;
import com.minigit.command.StatusCommand;
import com.minigit.command.UndoCommand;
import com.minigit.command.UnstageCommand;
import com.minigit.core.Reflog;
import com.minigit.core.Repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

public class MiniGitApplication {

    public static void main(String[] args) {

        if (args.length == 0) {
            printUsage();
            return;
        }

        String command = args[0];

        try {
            switch (command) {

                case "init" -> {
                    Path directory = args.length >= 2
                            ? Path.of(args[1])
                            : Path.of(".");

                    Repository repository = new Repository(directory);

                    repository.init();

                    System.out.println(
                            "Initialized empty MiniGit repository in "
                                    + repository.getMetadataDirectory()
                    );
                }

                case "add" -> {
                    if (args.length < 2) {
                        throw new IllegalArgumentException(
                                "Usage: add <file-path>"
                        );
                    }

                    Repository repository =
                            new Repository(Path.of("."));

                    new AddCommand(repository).execute(args[1]);
                }

                case "status" -> {
                    Repository repository =
                            new Repository(Path.of("."));

                    new StatusCommand(repository).execute();
                }

                case "commit" -> {
                    if (args.length < 2) {
                        throw new IllegalArgumentException(
                                "Usage: commit <message>"
                        );
                    }

                    String message = String.join(
                            " ",
                            Arrays.copyOfRange(args, 1, args.length)
                    );

                    Repository repository =
                            new Repository(Path.of("."));

                    new CommitCommand(repository).execute(message);
                }

                case "branch" -> {
                    Repository repository =
                            new Repository(Path.of("."));

                    BranchCommand branchCommand =
                            new BranchCommand(repository);

                    if (args.length == 1) {
                        branchCommand.list();

                    } else if ("-d".equals(args[1])
                            || "-D".equals(args[1])) {

                        if (args.length < 3) {
                            throw new IllegalArgumentException(
                                    "Usage: branch -d <name>"
                            );
                        }

                        branchCommand.delete(
                                args[2], "-D".equals(args[1])
                        );

                    } else {
                        branchCommand.create(
                                args[1],
                                args.length >= 3 ? args[2] : null
                        );
                    }
                }

                case "checkout" -> {
                    boolean create = args.length >= 2
                            && "-b".equals(args[1]);

                    int nameIndex = create ? 2 : 1;

                    if (args.length <= nameIndex) {
                        throw new IllegalArgumentException(
                                "Usage: checkout [-b] <branch-name>"
                        );
                    }

                    Repository repository =
                            new Repository(Path.of("."));

                    new CheckoutCommand(repository)
                            .execute(args[nameIndex], create);
                }

                case "unstage" -> {
                    if (args.length < 2) {
                        throw new IllegalArgumentException(
                                "Usage: unstage <file-path> | unstage --all"
                        );
                    }

                    Repository repository =
                            new Repository(Path.of("."));

                    UnstageCommand unstageCommand =
                            new UnstageCommand(repository);

                    if ("--all".equals(args[1])) {
                        unstageCommand.executeAll();
                    } else {
                        unstageCommand.execute(args[1]);
                    }
                }

                case "undo" -> {
                    Repository repository =
                            new Repository(Path.of("."));

                    new UndoCommand(repository).execute();
                }

                case "reflog" -> {
                    Repository repository =
                            new Repository(Path.of("."));

                    if (!repository.isInitialized()) {
                        throw new IllegalStateException(
                                "MiniGit repository is not initialized. "
                                        + "Run 'init' first."
                        );
                    }

                    var entries = Reflog.read(
                            repository.getMetadataDirectory(), 50
                    );

                    if (entries.isEmpty()) {
                        System.out.println("Reflog is empty.");
                    }

                    for (Reflog.Entry entry : entries) {
                        System.out.println(
                                entry.timestamp() + "  "
                                        + entry.action() + "  "
                                        + entry.branch() + "  "
                                        + shortHash(entry.oldHash())
                                        + " -> "
                                        + shortHash(entry.newHash())
                                        + "  " + entry.detail()
                        );
                    }
                }

                default -> {
                    System.out.println("Unknown command: " + command);
                    printUsage();
                }
            }

        } catch (IOException
                 | IllegalArgumentException
                 | IllegalStateException e) {

            System.err.println(
                    "MiniGit error: " + e.getMessage()
            );

            System.exit(1);
        }
    }

    private static String shortHash(String hash) {
        return hash == null || hash.isEmpty()
                ? "(none)"
                : hash.substring(0, Math.min(12, hash.length()));
    }

    private static void printUsage() {

        System.out.println(
                "MiniGit - A Java-based version control system"
        );

        System.out.println();
        System.out.println("Usage:");

        System.out.println(
                "  mvn exec:java -Dexec.args=\"init\""
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"add <file-path>\""
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"status\""
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"commit <message>\""
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"branch\"                  (list)"
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"branch <name> [commit]\"  (create)"
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"branch -d <name>\"        (delete, -D forces)"
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"checkout [-b] <name>\""
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"unstage <file-path>|--all\""
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"undo\"                    (undo last commit)"
        );

        System.out.println(
                "  mvn exec:java -Dexec.args=\"reflog\""
        );
    }
}