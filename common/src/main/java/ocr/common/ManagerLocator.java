package ocr.common;

import software.amazon.awssdk.services.ec2.model.Instance;

import java.util.Optional;

/**
 * OWNER: Partner A.
 * Used by the Local App to find or start a Manager.
 */
public final class ManagerLocator
{
	private final Aws aws;

	public ManagerLocator(Aws aws)
	{
		this.aws = aws;
	}

	/**
	 * An active Manager serving fewer than maxClients clients (read from its ActiveClients tag).
	 * A Manager that is terminating has the tag "Terminating" and is never available.
	 * @return its instance id, or empty if none.
	 */
	public Optional<String> findAvailableManager(int maxClients)
	{
		for (Instance manager : aws.instancesWithRole(Config.ROLE_MANAGER))
		{
			String clients = Aws.tags(manager).getOrDefault(Config.ACTIVE_CLIENTS_TAG, "0");
			try
			{
				if (Integer.parseInt(clients) < maxClients)
				{
					return Optional.of(manager.instanceId());
				}
			}
			catch (NumberFormatException e)
			{
				// "Terminating" or garbage: not available
			}
		}
		return Optional.empty();
	}

	/** Starts a new Manager instance. Returns its instance id. */
	public String launchManager()
	{
		String id = aws.launch(Config.ROLE_MANAGER, UserData.manager(), 1).get(0);
		aws.setTag(id, Config.ACTIVE_CLIENTS_TAG, "0");
		System.out.println("[LocalApp] Launched Manager " + id);
		return id;
	}
}