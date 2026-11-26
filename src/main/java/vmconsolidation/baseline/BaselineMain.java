package vmconsolidation.baseline;

import vmconsolidation.Main;

import java.util.Arrays;

/** Separate entry point; baseline uses no evolutionary selector or migration cooldown. */
public final class BaselineMain {
    public static void main(String[] args) throws Exception {
        String[] effective = Arrays.copyOf(args, args.length + 3);
        effective[args.length] = "cooldown=0";
        effective[args.length + 1] = "selectionSlack=0";
        effective[args.length + 2] = "policy=thr_mmt_pabfd";
        Main.run(effective, true);
    }
}
