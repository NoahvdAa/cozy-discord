@file:OptIn(ExperimentalTime::class)

package org.quiltmc.community.modes.quilt.extensions.settings

import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.checks.anyGuild
import com.kotlindiscord.kord.extensions.checks.hasPermission
import com.kotlindiscord.kord.extensions.checks.types.CheckContext
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.optionalEnumChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.*
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.types.respond
import dev.kord.common.entity.Permission
import dev.kord.core.entity.channel.Category
import dev.kord.core.entity.channel.TopGuildMessageChannel
import dev.kord.core.event.guild.GuildCreateEvent
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.koin.core.component.inject
import org.quiltmc.community.MAIN_GUILD
import org.quiltmc.community.database.collections.GlobalSettingsCollection
import org.quiltmc.community.database.collections.ServerSettingsCollection
import org.quiltmc.community.database.entities.GlobalSettings
import org.quiltmc.community.database.entities.ServerSettings
import org.quiltmc.community.database.enums.QuiltServerType
import org.quiltmc.community.hasPermissionInMainGuild
import org.quiltmc.community.inToolchain
import org.quiltmc.community.modes.quilt.extensions.messagelog.MessageLogExtension
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

// TODO: Implement these settings in other parts of the bot, and add logging

class SettingsExtension : Extension() {
    override val name: String = "settings"

    private val logger = KotlinLogging.logger { }

    private val globalSettings: GlobalSettingsCollection by inject()
    private val serverSettings: ServerSettingsCollection by inject()

    private val messageLogExtension get() = bot.findExtension<MessageLogExtension>()

    override suspend fun setup() {
        if (globalSettings.get() == null) {
            logger.info { "Creating initial global settings entry" }

            GlobalSettings().save()
        }

        event<GuildCreateEvent> {
            action {
                val settings = serverSettings.get(event.guild.id)

                if (settings == null) {
                    logger.info { "Creating settings entry for guild: ${event.guild.name} (${event.guild.id.value})" }

                    serverSettings.set(ServerSettings(event.guild.id))
                } else if (settings.leaveServer) {
                    logger.info { "Leaving guild, as configured: ${event.guild.name} (${event.guild.id.value})" }

                    delay(Duration.seconds(2))

                    event.guild.leave()
                }
            }
        }

        ephemeralSlashCommand {
            name = "global-config"
            description = "Global Cozy configuration commands"

            check { hasPermissionInMainGuild(Permission.Administrator) }

            ephemeralSubCommand {
                name = "get"
                description = "Retrieve Cozy's global configuration"

                action {
                    val settings = globalSettings.get()!!

                    respond {
                        embed {
                            settings.apply(this)
                        }
                    }
                }
            }

            ephemeralSubCommand(::InviteArg) {
                name = "appeals-invite"
                description = "Set or get the invite code used to invite banned users to the appeals server"

                action {
                    val settings = globalSettings.get()!!

                    if (arguments.inviteCode == null) {
                        respond {
                            content = "**Current invite:** https://discord.gg/${settings.appealsInvite}"
                        }

                        return@action
                    }

                    var code = arguments.inviteCode!!

                    if ("/" in code) {
                        code = code.split("/").last()
                    }

                    settings.appealsInvite = code
                    settings.save()

                    respond {
                        content = "**New invite set:** https://discord.gg/${settings.appealsInvite}"
                    }
                }
            }

            ephemeralSubCommand(::TokenArg) {
                name = "github-token"
                description = "Set the GitHub login token used by the GitHub commands"

                action {
                    val settings = globalSettings.get()!!

                    settings.githubToken = arguments.loginToken
                    settings.save()

                    respond {
                        content = "**GitHub login token set successfully**"
                    }
                }
            }

            ephemeralSubCommand(::TopMessageChannelArg) {
                name = "github-log-channel"
                description = "Set or get the channel used for logging GitHub command actions"

                check { inToolchain() }

                action {
                    val settings = globalSettings.get()!!

                    if (arguments.channel == null) {
                        respond {
                            content = "**Current GitHub log channel:** <#${settings.githubLogChannel}>"
                        }

                        return@action
                    }

                    settings.githubLogChannel = arguments.channel!!.id
                    settings.save()

                    respond {
                        content = "**New GitHub log channel set:** <#${settings.githubLogChannel}>"
                    }
                }
            }

            ephemeralSubCommand(::GuildArg) {
                name = "add-guild"
                description = "Mark a server as an official Quilt server"

                action {
                    val settings = globalSettings.get()!!

                    if (arguments.server.id in settings.quiltGuilds) {
                        respond {
                            content = ":x: **${arguments.server.name}** is already marked as an official Quilt guild."
                        }

                        return@action
                    }

                    settings.quiltGuilds.add(arguments.server.id)
                    settings.save()

                    respond {
                        content = "**${arguments.server.name}** marked as an official Quilt guild."
                    }
                }
            }

            ephemeralSubCommand(::GuildSnowflakeArg) {
                name = "remove-guild"
                description = "Unmark a server as an official Quilt server"

                action {
                    val settings = globalSettings.get()!!

                    if (arguments.serverId !in settings.quiltGuilds) {
                        respond {
                            content = ":x: `${arguments.serverId.value}` is not marked as an official Quilt guild."
                        }

                        return@action
                    }

                    settings.quiltGuilds.remove(arguments.serverId)
                    settings.save()

                    respond {
                        content = "`${arguments.serverId.value}` is no longer marked as an official Quilt guild."
                    }
                }
            }
        }

        ephemeralSlashCommand {
            name = "server-config"
            description = "Server-specific Cozy configuration commands"

            check { anyGuild() }
            check {
                hasPermissionInMainGuild(Permission.Administrator)

                if (!passed) {
                    passed = true
                    hasPermission(Permission.Administrator)
                }
            }

            ephemeralSubCommand(::OptionalGuildSnowflakeArg) {
                name = "get"
                description = "Retrieve Cozy's server configuration"

                action {
                    val context = CheckContext(event, getLocale())

                    if (arguments.serverId != null) {
                        context.hasPermissionInMainGuild(Permission.Administrator)

                        if (!context.passed) {
                            respond {
                                content = ":x: Only Quilt community managers can modify settings for other servers."
                            }

                            return@action
                        }
                    }

                    val settings = if (arguments.serverId == null) {
                        serverSettings.get(guild!!.id)
                    } else {
                        serverSettings.get(arguments.serverId!!)
                    }

                    if (settings == null) {
                        respond {
                            content = "Unknown guild ID: `${arguments.serverId?.value}`"
                        }

                        return@action
                    }

                    respond {
                        embed {
                            settings.apply(
                                this,
                                arguments.serverId != null ||
                                        settings.quiltServerType != null ||
                                        guild?.id == MAIN_GUILD
                            )
                        }
                    }
                }
            }

            ephemeralSubCommand(::PrefixServerArg) {
                name = "command-prefix"
                description = "Configure Cozy's command prefix"

                action {
                    val context = CheckContext(event, getLocale())

                    if (arguments.serverId != null) {
                        context.hasPermissionInMainGuild(Permission.Administrator)

                        if (!context.passed) {
                            respond {
                                content = ":x: Only Quilt community managers can modify settings for other servers."
                            }

                            return@action
                        }
                    }

                    val settings = if (arguments.serverId == null) {
                        serverSettings.get(guild!!.id)
                    } else {
                        serverSettings.get(arguments.serverId!!)
                    }

                    if (settings == null) {
                        respond {
                            content = "Unknown guild ID: `${arguments.serverId?.value}`"
                        }

                        return@action
                    }

                    settings.commandPrefix = arguments.prefix
                    settings.save()

                    respond {
                        content = "**Command prefix set:** `${settings.commandPrefix}`"
                    }
                }
            }

            ephemeralSubCommand(::RoleServerArg) {
                name = "add-moderator-role"
                description = "Add a role that should be given moderator permissions"

                action {
                    val context = CheckContext(event, getLocale())

                    if (arguments.serverId != null) {
                        context.hasPermissionInMainGuild(Permission.Administrator)

                        if (!context.passed) {
                            respond {
                                content = ":x: Only Quilt community managers can modify settings for other servers."
                            }

                            return@action
                        }
                    }

                    val settings = if (arguments.serverId == null) {
                        serverSettings.get(guild!!.id)
                    } else {
                        serverSettings.get(arguments.serverId!!)
                    }

                    if (settings == null) {
                        respond {
                            content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                        }

                        return@action
                    }

                    if (arguments.role.guildId != settings._id) {
                        respond {
                            content = ":x: That role doesn't belong to the guild with ID: `${settings._id.value}`"
                        }

                        return@action
                    }

                    if (arguments.role.id in settings.moderatorRoles) {
                        respond {
                            content = ":x: That role is already marked as a moderator role"
                        }

                        return@action
                    }

                    settings.moderatorRoles.add(arguments.role.id)
                    settings.save()

                    respond {
                        content = "Moderator role added: ${arguments.role.mention}"
                    }
                }
            }

            ephemeralSubCommand(::RoleServerArg) {
                name = "remove-moderator-role"
                description = "Remove a configured moderator role"

                action {
                    val context = CheckContext(event, getLocale())

                    if (arguments.serverId != null) {
                        context.hasPermissionInMainGuild(Permission.Administrator)

                        if (!context.passed) {
                            respond {
                                content = ":x: Only Quilt community managers can modify settings for other servers."
                            }

                            return@action
                        }
                    }

                    val settings = if (arguments.serverId == null) {
                        serverSettings.get(guild!!.id)
                    } else {
                        serverSettings.get(arguments.serverId!!)
                    }

                    if (settings == null) {
                        respond {
                            content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                        }

                        return@action
                    }

                    if (arguments.role.guildId != settings._id) {
                        respond {
                            content = ":x: That role doesn't belong to the guild with ID: `${settings._id.value}`"
                        }

                        return@action
                    }

                    if (arguments.role.id !in settings.moderatorRoles) {
                        respond {
                            content = ":x: That role is not marked as a moderator role"
                        }

                        return@action
                    }

                    settings.moderatorRoles.remove(arguments.role.id)
                    settings.save()

                    respond {
                        content = "Moderator role removed: ${arguments.role.mention}"
                    }
                }
            }

            ephemeralSubCommand(::TopMessageChannelGuildArg) {
                name = "cozy-log-channel"
                description = "Configure the channel Cozy should send log messages to"

                action {
                    val context = CheckContext(event, getLocale())

                    if (arguments.serverId != null) {
                        context.hasPermissionInMainGuild(Permission.Administrator)

                        if (!context.passed) {
                            respond {
                                content = ":x: Only Quilt community managers can modify settings for other servers."
                            }

                            return@action
                        }
                    }

                    val settings = if (arguments.serverId == null) {
                        serverSettings.get(guild!!.id)
                    } else {
                        serverSettings.get(arguments.serverId!!)
                    }

                    if (settings == null) {
                        respond {
                            content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                        }

                        return@action
                    }

                    if (arguments.channel == null) {
                        respond {
                            "**Current Cozy logging channel:** <#${settings.cozyLogChannel?.value}>"
                        }

                        return@action
                    }

                    val channel = arguments.channel as TopGuildMessageChannel

                    if (channel.guildId != settings._id) {
                        respond {
                            content = ":x: That channel doesn't belong to the guild with ID: `${settings._id.value}`"
                        }

                        return@action
                    }

                    settings.cozyLogChannel = channel.id
                    settings.save()

                    respond {
                        "**Cozy logging channel set:** ${channel.mention}"
                    }
                }
            }

            ephemeralSubCommand(::CategoryGuildArg) {
                name = "message-log-category"
                description = "Configure the category Cozy should use for message logs"

                action {
                    val context = CheckContext(event, getLocale())

                    if (arguments.serverId != null) {
                        context.hasPermissionInMainGuild(Permission.Administrator)

                        if (!context.passed) {
                            respond {
                                content = ":x: Only Quilt community managers can modify settings for other servers."
                            }

                            return@action
                        }
                    }

                    val settings = if (arguments.serverId == null) {
                        serverSettings.get(guild!!.id)
                    } else {
                        serverSettings.get(arguments.serverId!!)
                    }

                    if (settings == null) {
                        respond {
                            content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                        }

                        return@action
                    }

                    if (arguments.category == null) {
                        respond {
                            "**Current message log category:** <#${settings.messageLogCategory?.value}>"
                        }

                        return@action
                    }

                    val category = arguments.category as Category

                    if (category.guildId != settings._id) {
                        respond {
                            content = ":x: That category doesn't belong to the guild with ID: `${settings._id.value}`"
                        }

                        return@action
                    }

                    settings.messageLogCategory = category.id
                    settings.save()

                    respond {
                        "**Message log category set:** ${category.mention}"
                    }

                    event.kord.launch {
                        // Trigger a rotation, to be safe.
                        messageLogExtension?.getRotator(settings._id)?.populate()
                    }
                }
            }

            ephemeralSubCommand(::QuiltServerTypeArg) {
                name = "quilt-server-type"
                description = "For Quilt servers: Set or remove the Quilt server type flag for a server"

                check { hasPermissionInMainGuild(Permission.Administrator) }

                action {
                    val settings = if (arguments.serverId == null) {
                        serverSettings.get(guild!!.id)
                    } else {
                        serverSettings.get(arguments.serverId!!)
                    }

                    if (settings == null) {
                        respond {
                            content = ":x: Unknown guild ID: `${arguments.serverId?.value}`"
                        }

                        return@action
                    }

                    if (arguments.type != null) {
                        val existingServers = serverSettings.getByServerType(arguments.type).toList()

                        if (existingServers.isNotEmpty()) {
                            respond {
                                content = ":x: The following servers are already flagged as the" +
                                        " ${arguments.type!!.readableName} server: \n\n" +

                                        existingServers.joinToString("\n") { "`${it._id}`" }
                            }

                            return@action
                        }
                    }

                    settings.quiltServerType = arguments.type
                    settings.save()

                    respond {
                        content = if (settings.quiltServerType == null) {
                            "**Server no longer flagged as a Quilt server:** `${settings._id.value}`"
                        } else {
                            "**Server flagged as the ${settings.quiltServerType!!.readableName} server:** " +
                                    "`${settings._id.value}`"
                        }
                    }
                }
            }

            ephemeralSubCommand(::ShouldLeaveArg) {
                name = "set-leave-server"
                description = "For Quilt servers: Set whether Cozy should automatically leave a server"

                check { hasPermissionInMainGuild(Permission.Administrator) }

                action {
                    val settings = if (arguments.serverId == null) {
                        serverSettings.get(guild!!.id)
                    } else {
                        serverSettings.get(arguments.serverId!!)
                    } ?: ServerSettings(guild!!.id)

                    settings.leaveServer = arguments.shouldLeave
                    settings.save()

                    respond {
                        content = if (arguments.shouldLeave) {
                            "**Server will now be left automatically:** `${settings._id.value}`"
                        } else {
                            "**Server will not left automatically:** `${settings._id.value}`"
                        }
                    }

                    if (settings.leaveServer) {
                        event.kord.getGuild(settings._id)?.leave()
                    }
                }
            }
        }
    }

    inner class InviteArg : Arguments() {
        val inviteCode by optionalString("invite-code", "Invite code to use")
    }

    inner class TokenArg : Arguments() {
        val loginToken by string("login-token", "Login token to use")
    }

    inner class TopMessageChannelArg : Arguments() {
        val channel by optionalChannel("channel", "Channel to use") { _, channel ->
            if (channel != null && channel !is TopGuildMessageChannel) {
                throw DiscordRelayedException("${channel.mention} isn't a guild message channel")
            }
        }
    }

    inner class TopMessageChannelGuildArg : Arguments() {
        val channel by optionalChannel("channel", "Channel to use") { _, channel ->
            if (channel != null && channel !is TopGuildMessageChannel) {
                throw DiscordRelayedException("${channel.mention} isn't a guild message channel")
            }
        }

        val serverId by optionalSnowflake("server", "Server ID, if not the current one")
    }

    inner class GuildArg : Arguments() {
        val server by guild("server", "Server ID to use")
    }

    inner class GuildSnowflakeArg : Arguments() {
        val serverId by snowflake("server", "Server ID to use")
    }

    inner class OptionalGuildSnowflakeArg : Arguments() {
        val serverId by optionalSnowflake("server", "Server ID, if not the current one")
    }

    inner class CategoryGuildArg : Arguments() {
        val category by optionalChannel("category", "Category to use") { _, channel ->
            if (channel != null && channel !is Category) {
                throw DiscordRelayedException("${channel.mention} isn't a category")
            }
        }

        val serverId by optionalSnowflake("server", "Server ID, if not the current one")
    }

    inner class QuiltServerTypeArg : Arguments() {
        val type by optionalEnumChoice<QuiltServerType>(
            "type",
            "Quilt server type",
            "Server type"
        )

        val serverId by optionalSnowflake("server", "Server ID, if not the current one")
    }

    inner class ShouldLeaveArg : Arguments() {
        val shouldLeave by boolean("should-leave", "Whether Cozy should leave the server automatically")
        val serverId by optionalSnowflake("server", "Server ID, if not the current one")
    }

    inner class PrefixServerArg : Arguments() {
        val prefix by string("prefix", "Command prefix to set")
        val serverId by optionalSnowflake("server", "Server ID, if not the current one")
    }

    inner class RoleServerArg : Arguments() {
        val role by role("role", "Role to add/remove")
        val serverId by optionalSnowflake("server", "Server ID, if not the current one")
    }
}