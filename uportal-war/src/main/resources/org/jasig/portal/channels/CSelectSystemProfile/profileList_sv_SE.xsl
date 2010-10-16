<?xml version="1.0" encoding="utf-8"?>
<!--

    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.

-->

<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:param name="baseActionURL">baseActionURL_false</xsl:param>
  <xsl:param name="locale">sv_SE</xsl:param>
  <xsl:variable name="baseMediaURL">media/org/jasig/portal/channels/CUserPreferences/</xsl:variable>
  <xsl:template match="profiles">
        <div align="center">
          <center>
	<p class="uportal-channel-warning">Portalen har inte stött på din version av webbläsare tidigare.<br/>
Välj en lämplig profil för webbläsaren, tack!</p>
            <table border="0" width="100%" cellspacing="0" cellpadding="2">
              <xsl:apply-templates select="system"/>
            </table>
          </center>
        </div>
  </xsl:template>

  <xsl:template match="system">
    <tr>
      <td class="uportal-background-med" valign="top" colspan="5">
        <p class="uportal-channel-title">Systemprofiler</p>
      </td>
    </tr>
    <tr>
      <td class="uportal-background-light" valign="bottom" align="center">
        <p class="uportal-channel-subtitle">Val</p>
      </td>
      <td class="uportal-background-light" valign="bottom">
        <img alt="" border="0" width="10" height="10">
          <xsl:attribute name="src"><xsl:value-of select="string($baseMediaURL)"/>transparent.gif</xsl:attribute>
        </img>
      </td>
      <td class="uportal-background-light" valign="bottom">
        <p class="uportal-channel-subtitle">Profilnamn</p>
      </td>
      <td class="uportal-background-light" valign="bottom">
        <img alt="" border="0" width="10" height="10">
          <xsl:attribute name="src"><xsl:value-of select="string($baseMediaURL)"/>transparent.gif</xsl:attribute>
        </img>
      </td>
      <td width="60%" class="uportal-background-light" valign="bottom">
        <p class="uportal-channel-subtitle">Profilbeskrivning</p>
      </td>
    </tr>
    <xsl:apply-templates mode="system"/>
  </xsl:template>
  <xsl:template match="profile" mode="system">
        <tr>
          <td class="uportal-text" valign="top" align="center">
            <a>
              <xsl:attribute name="href"><xsl:value-of select="string($baseActionURL)"/>?action=map&amp;profileId=<xsl:value-of select="@id"/>&amp;profileType=system</xsl:attribute>
              <img border="0" width="16" height="16" alt="Knyt webbläsaren till denna profil">
                <xsl:attribute name="src"><xsl:value-of select="string($baseMediaURL)"/>profile_map.gif</xsl:attribute>
              </img>
            </a>
          </td>
          <td class="uportal-text" valign="top" align="center"/>
          <td class="uportal-text" valign="top">
            <xsl:value-of select="@name"/>
          </td>
          <td class="uportal-text" valign="top" align="center"/>
          <td width="60%" class="uportal-text" valign="top">
            <xsl:value-of select="description"/>
          </td>
        </tr>
  </xsl:template>
</xsl:stylesheet>