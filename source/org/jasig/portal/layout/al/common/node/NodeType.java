/* Copyright 2004 The JA-SIG Collaborative.  All rights reserved.
 * See license distributed with this file and
 * available online at http://www.uportal.org/license.html
 */

package org.jasig.portal.layout.al.common.node;

/**
 * Node type constant class
 * 
 * @author Peter Kharchenko: pkharchenko at unicon.net
 * @author Michael Ivanov: mvi at immagic.com
 */
public class NodeType {
	
    private final String name;
    
    public final static NodeType CHANNEL=new NodeType("channel");
    public final static NodeType FOLDER=new NodeType("folder");
    
    protected NodeType(String name) {
        this.name=name;
    }
    
    /**
     * @return Returns the name.
     */
    public String getName() {
        return name;
    }
    
    public String toString() {
      	return name;
    }
      
    public boolean equals ( Object obj ) {
      	return ( (obj instanceof NodeType) && obj.toString().equals(name) );
    }
    
}
