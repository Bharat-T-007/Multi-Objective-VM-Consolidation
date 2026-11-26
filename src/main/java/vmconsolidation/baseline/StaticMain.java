package vmconsolidation.baseline;

import vmconsolidation.Main;

import java.util.Arrays;

/** Static seeded-random placement baseline without dynamic VM migration. */
public final class StaticMain {
    public static void main(String[] args) throws Exception {
        String[] effective = Arrays.copyOf(args, args.length + 3);
        effective[args.length] = "cooldown=0";
        effective[args.length + 1] = "selectionSlack=0";
        effective[args.length + 2] = "policy=static";
        Main.run(effective, false);
    }
}
