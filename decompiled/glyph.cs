

// Assembly-CSharp, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null
// SuperStructureProjector.Glyph
using System;
using RWCustom;
using UnityEngine;

public abstract class Glyph : SuperStructureProjectorPart
{
	public IntVector2 pos;

	public float glyphCol;

	public int life;

	public int counter;

	public Glyph(SuperStructureProjector projector, IntVector2 pos)
		: base(projector)
	{
		glyphCol = UnityEngine.Random.value;
		this.pos = pos;
		life = Math.Max(life, UnityEngine.Random.Range(20, 200));
		projector.glyphsList.Add(this);
		projector.glyphGrid[pos.x, pos.y] = this;
	}

	public override void Update(bool eu)
	{
		base.Update(eu);
		counter++;
		if (counter >= life)
		{
			if (this is SingleGlyph)
			{
				(this as SingleGlyph).DeActivate();
			}
			else
			{
				Destroy();
			}
		}
	}

	public void Move(IntVector2 movement)
	{
		if (pos.x >= 0 && pos.y >= 0 && pos.x < projector.glyphGrid.GetLength(0) && pos.y < projector.glyphGrid.GetLength(1))
		{
			projector.glyphGrid[pos.x, pos.y] = null;
		}
		pos += movement;
		if (pos.x < 0)
		{
			pos.x += projector.glyphGrid.GetLength(0);
		}
		else if (pos.x >= projector.glyphGrid.GetLength(0))
		{
			pos.x -= projector.glyphGrid.GetLength(0);
		}
		if (pos.y < 0)
		{
			pos.y += projector.glyphGrid.GetLength(1);
		}
		else if (pos.y >= projector.glyphGrid.GetLength(1))
		{
			pos.y -= projector.glyphGrid.GetLength(1);
		}
		projector.glyphGrid[pos.x, pos.y] = this;
	}

	public override void Destroy()
	{
		if (pos.x >= 0 && pos.y >= 0 && pos.x < projector.glyphGrid.GetLength(0) && pos.y < projector.glyphGrid.GetLength(1))
		{
			projector.glyphGrid[pos.x, pos.y] = null;
		}
		base.Destroy();
	}
}
