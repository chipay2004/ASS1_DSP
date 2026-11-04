package ocr.common;

import java.util.Optional;

/**
 * OWNER: Partner A.
 * Used by the Local App to find or start a Manager.
 * Until A implements it, these stubs let B run the Local App against a Manager started by hand.
 */
public final class ManagerLocator {
    private final Aws aws;

    public ManagerLocator(Aws aws) { this.aws = aws; }

    /**
     * An active Manager serving fewer than maxClients clients (read from its ActiveClients tag).
     * @return its instance id, or empty if none.
     */
    public Optional<String> findAvailableManager(int maxClients) {
        // TODO(A): aws.instancesWithRole(Config.ROLE_MANAGER), read Config.ACTIVE_CLIENTS_TAG, compare to maxClients
        System.out.println("[stub] findAvailableManager: assuming a Manager runs locally");
        return Optional.of("local-stub");
    }

    /** Starts a new Manager instance with the manager user-data script. Returns its instance id. */
    public String launchManager() {
        // TODO(A): load scripts/manager-userdata.sh, aws.launch(Config.ROLE_MANAGER, script, 1).get(0)
        throw new UnsupportedOperationException("TODO(A): launchManager");
    }
}
