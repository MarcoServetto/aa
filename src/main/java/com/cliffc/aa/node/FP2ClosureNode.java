package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeFunPtr;
import com.cliffc.aa.type.TypeMemPtr;
import com.cliffc.aa.type.TypeTuple;

// Extract a Display pointer (a TypeMemPtr) from a TypeFunPtr.
public final class FP2ClosureNode extends Node {
  public FP2ClosureNode( Node funptr ) {
    super(OP_FP2CLO,funptr);
  }
  @Override public Node ideal(GVNGCM gvn, int level) {
    // If copy is dead, optimize for it.
    Node c = in(0).is_copy(gvn,2);
    if( c != null )
      set_def(0,c,gvn);
    // If at a FunPtrNode, it is only making a TFP out of a code pointer and a
    // display.  Become the display (dropping the code pointer).
    if( in(0) instanceof FunPtrNode )
      return in(0).in(1);
      
    return c==null ? null : this;
  }
  @Override public Type value(GVNGCM gvn) {
    TypeTuple tcall = (TypeTuple)gvn.type(in(0));
    Type tdisp = convert(tcall.at(2));
    assert tdisp.is_display_ptr();
    return tdisp;
  }
  @Override public TypeMemPtr all_type() { return TypeMemPtr.DISPLAY_PTR; }
  static public Type convert( Type t ) {
    if( !(t instanceof TypeFunPtr) )
      return t.above_center() ? TypeMemPtr.DISPLAY_PTR.dual() : TypeMemPtr.DISPLAY_PTR;
    TypeFunPtr tfp = (TypeFunPtr)t;
    return tfp.display();
  }
}
