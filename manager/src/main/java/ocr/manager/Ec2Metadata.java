package ocr.manager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks EC2 "which instance am I?" (IMDSv2).
 * Returns null when not running on EC2 (e.g. on your laptop).
 */
final class Ec2Metadata
{
	private Ec2Metadata() {}

	static String instanceId()
	{
		try
		{
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
			HttpRequest tokenReq = HttpRequest.newBuilder(URI.create("http://169.254.169.254/latest/api/token"))
					.timeout(Duration.ofSeconds(2))
					.header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
					.PUT(HttpRequest.BodyPublishers.noBody())
					.build();
			String token = http.send(tokenReq, HttpResponse.BodyHandlers.ofString()).body();

			HttpRequest idReq = HttpRequest.newBuilder(URI.create("http://169.254.169.254/latest/meta-data/instance-id"))
					.timeout(Duration.ofSeconds(2))
					.header("X-aws-ec2-metadata-token", token)
					.GET()
					.build();
			HttpResponse<String> res = http.send(idReq, HttpResponse.BodyHandlers.ofString());
			return res.statusCode() == 200 ? res.body().trim() : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
