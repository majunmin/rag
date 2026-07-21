package com.majm.rag.ingestion;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

@Component
public class WebUrlPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public URI validate(String rawUrl) {
        if (StringUtils.isBlank(rawUrl)) {
            throw new IllegalArgumentException("URL is required");
        }

        URI parsed;
        try {
            parsed = new URI(StringUtils.trim(rawUrl));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL is invalid", e);
        }

        String scheme = StringUtils.lowerCase(parsed.getScheme(), Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("URL must use http or https");
        }
        if (StringUtils.isBlank(parsed.getHost())) {
            throw new IllegalArgumentException("URL host is required");
        }
        if (parsed.getUserInfo() != null) {
            throw new IllegalArgumentException("URL credentials are not allowed");
        }

        ensurePublicHost(parsed.getHost());
        String asciiUrl = parsed.toASCIIString();
        int fragmentStart = asciiUrl.indexOf('#');
        int end = fragmentStart >= 0 ? fragmentStart : asciiUrl.length();
        return URI.create(scheme + asciiUrl.substring(parsed.getScheme().length(), end));
    }

    private void ensurePublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("URL host cannot be resolved", e);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException("URL must resolve only to public addresses");
            }
        }
    }

    private boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return false;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first != 0
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 198 && (second == 18 || second == 19))
                && first < 224;
        }
        return (Byte.toUnsignedInt(bytes[0]) & 0xfe) != 0xfc;
    }
}
