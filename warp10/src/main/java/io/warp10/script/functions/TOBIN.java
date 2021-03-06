//
//   Copyright 2018  SenX S.A.S.
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.script.functions;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;

import org.bouncycastle.util.encoders.Hex;

import com.google.common.base.Charsets;

/**
 * Encode a String in binary
 */
public class TOBIN extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  private static final String[] NIBBLES = new String[] {
    "0000", "0001", "0010", "0011",
    "0100", "0101", "0110", "0111",
    "1000", "1001", "1010", "1011",
    "1100", "1101", "1110", "1111",
  };
  
  public TOBIN(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object o = stack.pop();
  
    byte[] data = null;
    
    if (o instanceof String) {
      data = o.toString().getBytes(Charsets.UTF_8);
    } else if (o instanceof byte[]) {
      data = (byte[]) o;
    }
    
    if (null == data) {
      throw new WarpScriptException(getName() + " operates on a String or a byte array.");
    }

    StringBuilder sb = new StringBuilder();
    
    for (int i = 0; i < data.length; i++) {
      int nibble = (data[i] >>> 4) & 0xF;
      sb.append(NIBBLES[nibble]);
      nibble = (data[i] & 0xF);
      sb.append(NIBBLES[nibble]);
    }
    
    stack.push(sb.toString());
    
    return stack;
  }
}
