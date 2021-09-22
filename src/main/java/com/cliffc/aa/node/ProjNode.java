package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.*;
import com.cliffc.aa.tvar.TV2;
import static com.cliffc.aa.AA.unimpl;

// Proj data
public class ProjNode extends Node {
  public int _idx;
  public ProjNode( Node head, int idx ) { this(OP_PROJ,head,idx); }
  public ProjNode( int idx, Node... ns ) { super(OP_PROJ,ns); _idx=idx; }
  ProjNode( byte op, Node ifn, int idx ) {
    super(op,ifn);
    _idx=idx;
    if( in(0) instanceof NewNode )
      _tvar = in(0).tvar();     // Pre-unify
  }
  @Override public String xstr() { return "DProj"+_idx; }

  // Strictly reducing
  @Override public Node ideal_reduce() {
    Node c = in(0).is_copy(_idx);
    if( c != null ) return c==this ? Env.ANY : c; // Happens in dying loops
    return null;
  }
  @Override public Type value(GVNGCM.Mode opt_mode) {
    Type c = val(0);
    if( c instanceof TypeTuple ) {
      TypeTuple ct = (TypeTuple)c;
      if( _idx < ct._ts.length )
        return ct._ts[_idx];
    }
    return c.oob();
  }
  // Only called here if alive, and input is more-than-basic-alive
  @Override public TypeMem live_use(GVNGCM.Mode opt_mode, Node def ) {
    return def.all_live().basic_live() ? TypeMem.ALIVE : TypeMem.ANYMEM;
  }

  // Unify with the parent TVar sub-part
  @Override public boolean unify( Work work ) {
    TV2 tv = tvar();
    if( in(0) instanceof NewNode ) // TODO: Not really a proper use of Proj
      return tv.unify(tvar(0),work);
    if( in(0) instanceof CallEpiNode ) // Only DProj#2 and its the return value
      return tv.unify(tvar(0),work);
    if( in(0) instanceof CallNode )
      return tv.unify(in(0).tvar(_idx),work); // Unify with Call arguments
    throw unimpl();
  }

  public static ProjNode proj( Node head, int idx ) {
    for( Node use : head._uses )
      if( use instanceof ProjNode && ((ProjNode)use)._idx==idx )
        return (ProjNode)use;
    return null;
  }

  void set_idx( int idx ) { unelock(); _idx=idx; } // Unlock before changing hash
  @Override public int hashCode() { return super.hashCode()+_idx; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof ProjNode) ) return false;
    ProjNode proj = (ProjNode)o;
    return _idx==proj._idx;
  }
  @Override public byte op_prec() { return in(0).op_prec(); }
}
